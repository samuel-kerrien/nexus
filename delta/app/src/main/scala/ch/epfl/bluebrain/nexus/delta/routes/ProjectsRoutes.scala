package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import cats.data.OptionT
import cats.effect.unsafe.implicits._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.projects.{create => CreateProjects, delete => DeleteProjects, read => ReadProjects, write => WriteProjects}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources.{read => ReadResources}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{Projects, ProjectsConfig, ProjectsStatistics}
import ch.epfl.bluebrain.nexus.delta.sdk.provisioning.ProjectProvisioning
import kamon.instrumentation.akka.http.TracingDirectives.operationName

/**
  * The project routes
  * @param identities
  *   the identity module
  * @param aclCheck
  *   verify the acls for users
  * @param projects
  *   the projects module
  * @param projectsStatistics
  *   the statistics by project
  * @param schemeDirectives
  *   directives related to orgs and projects
  */
final class ProjectsRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    projects: Projects,
    projectsStatistics: ProjectsStatistics,
    projectProvisioning: ProjectProvisioning,
    schemeDirectives: DeltaSchemeDirectives
)(implicit
    baseUri: BaseUri,
    config: ProjectsConfig,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {

  import baseUri.prefixSegment
  import schemeDirectives._

  implicit val paginationConfig: PaginationConfig = config.pagination

  private def projectsSearchParams(implicit caller: Caller): Directive1[ProjectSearchParams] = {
    onSuccess(aclCheck.fetchAll.unsafeToFuture()).flatMap { allAcls =>
      (searchParams & parameter("label".?)).tmap { case (deprecated, rev, createdBy, updatedBy, label) =>
        ProjectSearchParams(
          None,
          deprecated,
          rev,
          createdBy,
          updatedBy,
          label,
          proj => aclCheck.authorizeFor(proj.ref, ReadProjects, allAcls)
        )
      }
    }
  }

  private def provisionProject(implicit caller: Caller): Directive0 = onSuccess(
    projectProvisioning(caller.subject).unsafeToFuture()
  )

  private def revisionParam: Directive[Tuple1[Int]] = parameter("rev".as[Int])

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("projects") {
        extractCaller { implicit caller =>
          concat(
            // List projects
            (get & pathEndOrSingleSlash & extractUri & fromPaginated & provisionProject & projectsSearchParams &
              sort[Project]) { (uri, pagination, params, order) =>
              operationName(s"$prefixSegment/projects") {
                implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectResource]] =
                  searchResultsJsonLdEncoder(Project.context, pagination, uri)

                emit(projects.list(pagination, params, order).widen[SearchResults[ProjectResource]])
              }
            },
            resolveProjectRef.apply { project =>
              concat(
                operationName(s"$prefixSegment/projects/{org}/{project}") {
                  concat(
                    (put & pathEndOrSingleSlash) {
                      parameter("rev".as[Int].?) {
                        case None      =>
                          // Create project
                          authorizeFor(project, CreateProjects).apply {
                            entity(as[ProjectFields]) { fields =>
                              emit(
                                StatusCodes.Created,
                                projects.create(project, fields).mapValue(_.metadata).attemptNarrow[ProjectRejection]
                              )
                            }
                          }
                        case Some(rev) =>
                          // Update project
                          authorizeFor(project, WriteProjects).apply {
                            entity(as[ProjectFields]) { fields =>
                              emit(
                                projects
                                  .update(project, rev, fields)
                                  .mapValue(_.metadata)
                                  .attemptNarrow[ProjectRejection]
                              )
                            }
                          }
                      }
                    },
                    (get & pathEndOrSingleSlash) {
                      parameter("rev".as[Int].?) {
                        case Some(rev) => // Fetch project at specific revision
                          authorizeFor(project, ReadProjects).apply {
                            emit(projects.fetchAt(project, rev).attemptNarrow[ProjectRejection])
                          }
                        case None      => // Fetch project
                          emitOrFusionRedirect(
                            project,
                            authorizeFor(project, ReadProjects).apply {
                              emit(projects.fetch(project).attemptNarrow[ProjectRejection])
                            }
                          )
                      }
                    },
                    // Deprecate/delete project
                    (delete & pathEndOrSingleSlash) {
                      parameters("rev".as[Int], "prune".?(false)) {
                        case (rev, true)  =>
                          authorizeFor(project, DeleteProjects).apply {
                            emit(projects.delete(project, rev).mapValue(_.metadata).attemptNarrow[ProjectRejection])
                          }
                        case (rev, false) =>
                          authorizeFor(project, WriteProjects).apply {
                            emit(projects.deprecate(project, rev).mapValue(_.metadata).attemptNarrow[ProjectRejection])
                          }
                      }
                    }
                  )
                },
                (pathPrefix("undeprecate") & put & revisionParam) { revision =>
                  authorizeFor(project, WriteProjects).apply {
                    emit(projects.undeprecate(project, revision).attemptNarrow[ProjectRejection])
                  }
                },
                operationName(s"$prefixSegment/projects/{org}/{project}/statistics") {
                  // Project statistics
                  (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                    authorizeFor(project, ReadResources).apply {
                      val stats = projectsStatistics.get(project)
                      emit(
                        OptionT(stats).toRight[ProjectRejection](ProjectNotFound(project)).value
                      )
                    }
                  }
                }
              )
            },
            // list projects for an organization
            (get & label & pathEndOrSingleSlash & extractUri & fromPaginated & provisionProject & projectsSearchParams &
              sort[Project]) { (organization, uri, pagination, params, order) =>
              implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectResource]] =
                searchResultsJsonLdEncoder(Project.context, pagination, uri)

              val filter = params.copy(organization = Some(organization))
              emit(projects.list(pagination, filter, order).widen[SearchResults[ProjectResource]])
            }
          )
        }
      }
    }
}

object ProjectsRoutes {

  /**
    * @return
    *   the [[Route]] for projects
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      projects: Projects,
      projectsStatistics: ProjectsStatistics,
      projectProvisioning: ProjectProvisioning,
      schemeDirectives: DeltaSchemeDirectives
  )(implicit
      baseUri: BaseUri,
      config: ProjectsConfig,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route =
    new ProjectsRoutes(identities, aclCheck, projects, projectsStatistics, projectProvisioning, schemeDirectives).routes

}
