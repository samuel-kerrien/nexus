package ch.epfl.bluebrain.nexus.delta.sdk.resources.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidationReport
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of Resource rejection types.
  *
  * @param reason
  *   a descriptive message as to why the rejection occurred
  */
sealed abstract class ResourceRejection(val reason: String) extends Rejection

object ResourceRejection {

  /**
    * Rejection that may occur when fetching a Resource
    */
  sealed abstract class ResourceFetchRejection(override val reason: String) extends ResourceRejection(reason)

  /**
    * Rejection returned when a subject intends to retrieve a resource at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided
    *   the provided revision
    * @param current
    *   the last known revision
    */
  final case class RevisionNotFound(provided: Int, current: Int)
      extends ResourceFetchRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when a subject intends to retrieve a resource at a specific tag, but the provided tag does not
    * exist.
    *
    * @param tag
    *   the provided tag
    */
  final case class TagNotFound(tag: UserTag) extends ResourceFetchRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to create a resource with an id that already exists.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project it belongs to
    */
  final case class ResourceAlreadyExists(id: Iri, project: ProjectRef)
      extends ResourceRejection(s"Resource '$id' already exists in project '$project'.")

  /**
    * Rejection returned when attempting to interact with a resource providing an id that cannot be resolved to an Iri.
    *
    * @param id
    *   the resource identifier
    */
  final case class InvalidResourceId(id: String)
      extends ResourceFetchRejection(s"Resource identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when attempting to create/update a resource with a reserved id.
    */
  final case class ReservedResourceId(id: Iri)
      extends ResourceRejection(s"Resource identifier '$id' is reserved for the platform.")

  /**
    * Rejection returned when attempting to update a resource with an id that doesn't exist.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project it belongs to
    */
  final case class ResourceNotFound(id: Iri, project: ProjectRef)
      extends ResourceFetchRejection(
        s"Resource '$id' not found in project '$project'."
      )

  /**
    * Rejection returned when attempting to update a resource and no change is introduced.
    *
    * @param currentState
    *   the current state of the resource
    */
  final case class NoChangeDetected(currentState: ResourceState)
      extends ResourceFetchRejection(
        s"No changes were detected when attempting to update resource '${currentState.id}' in project '${currentState.project}'."
      )

  /**
    * Rejection returned when attempting to create/update a resource where the payload does not satisfy the SHACL schema
    * constrains.
    *
    * @param id
    *   the resource identifier
    * @param schema
    *   the schema for which validation failed
    * @param report
    *   the SHACL validation failure report
    */
  final case class InvalidResource(id: Iri, schema: ResourceRef, report: ValidationReport, expanded: ExpandedJsonLd)
      extends ResourceRejection(
        s"Resource '$id' failed to validate against the constraints defined in schema '$schema'"
      )

  /**
    * Rejection returned when attempting to resolve ''schemaRef'' using resolvers on project ''projectRef''
    */
  final case class InvalidSchemaRejection(
      schemaRef: ResourceRef,
      projectRef: ProjectRef,
      report: ResourceResolutionReport
  ) extends ResourceRejection(s"Schema '$schemaRef' could not be resolved in '$projectRef'")

  final case class SchemaIsMandatory(project: ProjectRef)
      extends ResourceRejection(
        s"Project '$project' does not accept unconstrained resources. A schema must be provided."
      )

  /**
    * Rejection returned when attempting to update/deprecate a resource with a different schema than the resource
    * schema.
    *
    * @param id
    *   the resource identifier
    * @param provided
    *   the resource provided schema
    * @param expected
    *   the resource schema
    */
  final case class UnexpectedResourceSchema(id: Iri, provided: ResourceRef, expected: ResourceRef)
      extends ResourceRejection(
        s"Resource '$id' is not constrained by the provided schema '$provided', but by the schema '$expected'."
      )

  /**
    * Rejection returned when attempting to create a SHACL engine.
    *
    * @param id
    *   the resource identifier
    * @param schema
    *   the resource provided schema
    * @param details
    *   the SHACL engine errors
    */
  final case class ResourceShaclEngineRejection(id: Iri, schema: ResourceRef, details: String)
      extends ResourceRejection(s"Resource '$id' failed to produce a SHACL engine for schema '$schema'.")

  /**
    * Rejection returned when attempting to update/deprecate a resource that is already deprecated.
    *
    * @param id
    *   the resource identifier
    */
  final case class ResourceIsDeprecated(id: Iri) extends ResourceRejection(s"Resource '$id' is deprecated.")

  /**
    * Rejection returned when attempting to undeprecate a resource that is not deprecated
    * @param id
    *   the resource identifier
    */
  final case class ResourceIsNotDeprecated(id: Iri) extends ResourceRejection(s"Resource '$id' is not deprecated.")

  /**
    * Rejection returned when a subject intends to perform an operation on the current resource, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided
    *   the provided revision
    * @param expected
    *   the expected revision
    */
  final case class IncorrectRev(provided: Int, expected: Int)
      extends ResourceRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the resource may have been updated since last seen."
      )

  /**
    * Rejection returned when attempting to create/update a resource with a deprecated schema.
    *
    * @param schemaId
    *   the schema identifier
    */
  final case class SchemaIsDeprecated(schemaId: Iri) extends ResourceRejection(s"Schema '$schemaId' is deprecated.")

  /**
    * Rejection returned when attempting to do an operation that requires an explicit schema but the schema is not
    * provided.
    */
  final case object NoSchemaProvided extends ResourceRejection(s"A schema is required but was not provided.")

  implicit val resourceRejectionEncoder: Encoder.AsObject[ResourceRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case ResourceShaclEngineRejection(_, _, details) => obj.add("details", details.asJson)
        case InvalidResource(_, _, report, expanded)     =>
          obj.addContext(contexts.shacl).add("details", report.json).add("expanded", expanded.json)
        case InvalidSchemaRejection(_, _, report)        =>
          obj.addContext(contexts.resolvers).add("report", report.asJson)
        case IncorrectRev(provided, expected)            => obj.add("provided", provided.asJson).add("expected", expected.asJson)
        case _                                           => obj
      }
    }

  implicit val resourceRejectionJsonLdEncoder: JsonLdEncoder[ResourceRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val responseFieldsResources: HttpResponseFields[ResourceRejection] =
    HttpResponseFields {
      case RevisionNotFound(_, _)          => StatusCodes.NotFound
      case ResourceNotFound(_, _)          => StatusCodes.NotFound
      case TagNotFound(_)                  => StatusCodes.NotFound
      case InvalidSchemaRejection(_, _, _) => StatusCodes.NotFound
      case ResourceAlreadyExists(_, _)     => StatusCodes.Conflict
      case IncorrectRev(_, _)              => StatusCodes.Conflict
      case _                               => StatusCodes.BadRequest
    }
}
