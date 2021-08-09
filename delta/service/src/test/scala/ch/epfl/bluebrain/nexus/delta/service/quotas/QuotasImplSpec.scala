package ch.epfl.bluebrain.nexus.delta.service.quotas

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectsCountsDummy
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Identity, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection.{QuotaReached, QuotasDisabled, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.{Quota, QuotasConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, ServiceAccountConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{ConfigFixtures, ProjectSetup}
import ch.epfl.bluebrain.nexus.testkit.IOValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.UUID

class QuotasImplSpec extends AnyWordSpecLike with Matchers with IOValues with ConfigFixtures {

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val uuidF: UUIDF     = UUIDF.fixed(UUID.randomUUID())

  private val org           = Label.unsafe("myorg")
  private val project       = ProjectGen.project("myorg", "myproject")
  private val project2      = ProjectGen.project("myorg", "myproject2")
  private val (_, projects) = ProjectSetup.init(org :: Nil, project :: project2 :: Nil).accepted

  implicit private val config            = QuotasConfig(resources = 100, enabled = true, Map(project2.ref -> 200))
  implicit private val serviceAccountCfg = ServiceAccountConfig(ServiceAccount(User("internal", Label.unsafe("sa"))))

  private val projectsCounts =
    ProjectsCountsDummy(project.ref -> ProjectCount(events = 10, resources = 8, Instant.EPOCH))

  private val quotas         = new QuotasImpl(projects, projectsCounts)

  "Quotas" should {

    "be fetched from configuration" in {
      quotas.fetch(project.ref).accepted shouldEqual Quota(resources = 100)
      quotas.fetch(project2.ref).accepted shouldEqual Quota(resources = 200)
    }

    "failed to be fetched if project does not exist" in {
      val nonExisting = ProjectRef(Label.unsafe("a"), Label.unsafe("b"))
      quotas.fetch(nonExisting).rejectedWith[WrappedProjectRejection]
    }

    "failed to be fetched if quotas config is disabled" in {
      val quotas = new QuotasImpl(projects, projectsCounts)(config.copy(enabled = false), serviceAccountCfg)
      quotas.fetch(project.ref).rejectedWith[QuotasDisabled]
    }

    "not be reached" in {
      quotas.reachedForResources(project.ref, Anonymous).accepted
    }

    "not be reached when disabled" in {
      val quotas =
        new QuotasImpl(projects, projectsCounts)(config.copy(resources = 0, enabled = false), serviceAccountCfg)
      quotas.reachedForResources(project.ref, Anonymous).accepted
    }

    "be reached" in {
      val quotas = new QuotasImpl(projects, projectsCounts)(config.copy(resources = 8), serviceAccountCfg)
      quotas.reachedForResources(project.ref, Anonymous).rejected shouldEqual QuotaReached(project.ref, 8)

      quotas.reachedForResources(project.ref, serviceAccountCfg.value.subject).accepted
      quotas.reachedForResources(project2.ref, Anonymous).accepted
    }
  }

}