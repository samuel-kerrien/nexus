package ch.epfl.bluebrain.nexus.delta.plugins.archive

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.archive.Archives.{entityType, expandIri, ArchiveLog}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EphemeralLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.{EphemeralDefinition, EphemeralLog, Transactors}
import io.circe.Json

/**
  * Archives module.
  *
  * @param log
  *   the underlying ephemeral log
  * @param fetchContext
  *   to fetch the project context
  * @param archiveDownload
  *   the archive download logic
  * @param sourceDecoder
  *   a source decoder for [[ArchiveValue]]
  * @param config
  *   the log config
  * @param rcr
  *   the archive remote context resolution
  */
class Archives(
    log: ArchiveLog,
    fetchContext: FetchContext,
    archiveDownload: ArchiveDownload,
    sourceDecoder: JsonLdSourceDecoder[ArchiveValue],
    config: EphemeralLogConfig
)(implicit rcr: RemoteContextResolution) {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  /**
    * Creates an archive with a specific id.
    *
    * @param iri
    *   the archive identifier
    * @param project
    *   the archive parent project
    * @param value
    *   the archive value
    * @param subject
    *   the subject that initiated the action
    */
  def create(iri: Iri, project: ProjectRef, value: ArchiveValue)(implicit subject: Subject): IO[ArchiveResource] =
    eval(CreateArchive(iri, project, value, subject)).span("createArchive")

  /**
    * Creates an archive from a json-ld representation. If an id is detected in the source document it will be used.
    * Alternately, an id is generated by the system.
    *
    * @param project
    *   the archive parent project
    * @param source
    *   the archive json representation
    * @param subject
    *   the subject that initiated the action
    */
  def create(project: ProjectRef, source: Json)(implicit subject: Subject): IO[ArchiveResource] =
    (for {
      p            <- fetchContext.onRead(project)
      (iri, value) <- sourceDecoder(p, source)
      res          <- create(iri, project, value)
    } yield res).span("createArchive")

  /**
    * Creates an archive from a json-ld representation with a user specified id. If an id is also detected in the source
    * document it will be compared with the specified id. If the user specified id does not match the detected source
    * id, the call will be rejected.
    *
    * @param id
    *   the archive identifier
    * @param project
    *   the archive parent project
    * @param source
    *   the archive json representation
    * @param subject
    *   the subject that initiated the action
    */
  def create(
      id: IdSegment,
      project: ProjectRef,
      source: Json
  )(implicit subject: Subject): IO[ArchiveResource] =
    (for {
      (iri, p) <- expandWithContext(id, project)
      value    <- sourceDecoder(p, iri, source)
      res      <- create(iri, project, value)
    } yield res).span("createArchive")

  /**
    * Fetches an existing archive.
    *
    * @param id
    *   the archive identifier
    * @param project
    *   the archive parent project
    */
  def fetch(id: IdSegment, project: ProjectRef): IO[ArchiveResource] = {
    for {
      (iri, _) <- expandWithContext(id, project)
      state    <- log.stateOr(project, iri, ArchiveNotFound(iri, project))
      res       = state.toResource(config.ttl)
    } yield res
  }.span("fetchArchive")

  /**
    * Provides an [[AkkaSource]] for streaming an archive content.
    *
    * @param id
    *   the archive identifier
    * @param project
    *   the archive parent project
    * @param ignoreNotFound
    *   ignore resource and file references that do not exist or reject
    */
  def download(
      id: IdSegment,
      project: ProjectRef,
      ignoreNotFound: Boolean
  )(implicit caller: Caller): IO[AkkaSource] =
    (for {
      resource <- fetch(id, project)
      value     = resource.value
      source   <- archiveDownload(value.value, project, ignoreNotFound)
    } yield source).span("downloadArchive")

  private def expandWithContext(id: IdSegment, project: ProjectRef) =
    for {
      p   <- fetchContext.onRead(project)
      iri <- expandIri(id, p)
    } yield (iri, p)

  private def eval(cmd: CreateArchive): IO[ArchiveResource] =
    log.evaluate(cmd.project, cmd.id, cmd).map { _.toResource(config.ttl) }
}

object Archives {

  final val entityType: EntityType = EntityType("archive")

  type ArchiveLog = EphemeralLog[
    Iri,
    ArchiveState,
    CreateArchive,
    ArchiveRejection
  ]

  /**
    * Iri expansion logic for archives.
    */
  final val expandIri: ExpandIri[InvalidArchiveId] = new ExpandIri(InvalidArchiveId.apply)

  /**
    * The default archive API mappings
    */
  val mappings: ApiMappings = ApiMappings("archive" -> schema.original)

  /**
    * Constructs a new [[Archives]] module instance.
    */
  final def apply(
      fetchContext: FetchContext,
      archiveDownload: ArchiveDownload,
      cfg: ArchivePluginConfig,
      xas: Transactors,
      clock: Clock[IO]
  )(implicit api: JsonLdApi, uuidF: UUIDF, rcr: RemoteContextResolution): Archives = new Archives(
    EphemeralLog(
      definition(clock),
      cfg.ephemeral,
      xas
    ),
    fetchContext,
    archiveDownload,
    sourceDecoder,
    cfg.ephemeral
  )

  private def definition(clock: Clock[IO]) =
    EphemeralDefinition(
      entityType,
      evaluate(clock),
      ArchiveState.serializer,
      onUniqueViolation = (id: Iri, c: CreateArchive) => ResourceAlreadyExists(id, c.project)
    )

  private[archive] def sourceDecoder(implicit api: JsonLdApi, uuidF: UUIDF): JsonLdSourceDecoder[ArchiveValue] =
    new JsonLdSourceDecoder[ArchiveValue](contexts.archives, uuidF)

  private[archive] def evaluate(clock: Clock[IO])(
      command: CreateArchive
  ): IO[ArchiveState] =
    clock.realTimeInstant.map { now =>
      ArchiveState(command.id, command.project, command.value.resources, now, command.subject)
    }

}
