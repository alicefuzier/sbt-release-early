package ch.epfl.scala.sbt.release

import sbt.{AutoPlugin, Def, PluginTrigger, Plugins, Setting, Task}

object ReleaseEarlyPlugin extends AutoPlugin {
  object autoImport
      extends ReleaseEarlyKeys.ReleaseEarlySettings
      with ReleaseEarlyKeys.ReleaseEarlyTasks

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins =
    sbtdynver.DynVerPlugin && bintray.BintrayPlugin && xerial.sbt.Sonatype

  override def globalSettings: Seq[Def.Setting[_]] =
    ReleaseEarly.globalSettings
  override def projectSettings: Seq[Def.Setting[_]] =
    ReleaseEarly.projectSettings
  override def buildSettings: Seq[Def.Setting[_]] =
    ReleaseEarly.buildSettings
}

object ReleaseEarlyKeys {
  import sbt.{taskKey, settingKey, TaskKey, SettingKey}

  trait ReleaseEarlySettings {
    trait UnderlyingPublisher
    case object BintrayPublisher extends UnderlyingPublisher
    case object SonatypePublisher extends UnderlyingPublisher

    import sbt.Global
    private val localReleaseEarlyEnableLocalReleases: SettingKey[Boolean] =
      settingKey("Enable local releases.")
    val releaseEarlyEnableLocalReleases: SettingKey[Boolean] =
      localReleaseEarlyEnableLocalReleases in Global
    private val localReleaseEarlyInsideCI: SettingKey[Boolean] =
      settingKey("Detect whether sbt is running inside the CI.")
    val releaseEarlyInsideCI: SettingKey[Boolean] =
      localReleaseEarlyInsideCI in Global
    val releaseEarlyBypassSnapshotCheck: SettingKey[Boolean] =
      settingKey("Bypass snapshots check, not failing if snapshots are found.")
    val releaseEarlyProcess: SettingKey[Seq[TaskKey[Unit]]] =
      settingKey("Release process executed by `releaseEarly`.")
    val releaseEarlyWith: SettingKey[UnderlyingPublisher] =
      settingKey("Specify the publisher to publish your artifacts.")
  }

  trait ReleaseEarlyTasks {
    val releaseEarly: TaskKey[Unit] =
      taskKey("Release early, release often.")
    val releaseEarlyValidatePom: TaskKey[Unit] =
      taskKey("Validate the data to generate a POM file.")
    val releaseEarlySyncToMaven: TaskKey[Unit] =
      taskKey("Synchronize to Maven Central.")
    val releaseEarlyEnableSyncToMaven: SettingKey[Boolean] =
      settingKey("Enable synchronization to Maven Central for git tags.")
    val releaseEarlyCheckRequirements: TaskKey[Unit] =
      taskKey("Check the requirements of the environment.")
    val releaseEarlyCheckSnapshotDependencies: TaskKey[Unit] =
      taskKey("Check snapshot dependencies before the release.")
    val releaseEarlyPublish: TaskKey[Unit] =
      taskKey(s"Publish artifact. Defaults to ${sbt.Keys.publish.key.label}.")
    val releaseEarlyClose: TaskKey[Unit] =
      taskKey("Materialize the release by closing staging repositories.")
    val releaseEarlySonatypeCredentials: TaskKey[Seq[sbt.Credentials]] =
      taskKey("Fetch sonatype credentials from env and persists them.")
  }
}

object ReleaseEarly {
  import sbt.{Keys, Tags, SettingKey, settingKey}

  import ReleaseEarlyPlugin.autoImport._
  import xerial.sbt.Sonatype.{SonatypeCommand => Sonatype}
  import xerial.sbt.Sonatype.{autoImport => SonatypeKeys}
  import bintray.BintrayPlugin.{autoImport => Bintray}
  import sbtdynver.DynVerPlugin.{autoImport => DynVer}
  import com.typesafe.sbt.SbtPgp.{autoImport => Pgp}

  final val SingleThreadedRelease = Tags.Tag("single-threaded-release")

  val globalSettings: Seq[Setting[_]] = Seq(
    releaseEarlyInsideCI := Defaults.releaseEarlyInsideCI.value,
    releaseEarlyEnableLocalReleases := Defaults.releaseEarlyEnableLocalReleases.value,
    Keys.credentials := Defaults.releaseEarlySonatypeCredentials.value,
    // This is not working for now, see https://github.com/sbt/sbt-pgp/issues/111
    // When it's fixed, remove the scoped key in `buildSettings` and this will work
    Pgp.pgpPassphrase := Defaults.pgpPassphrase.value,
    Keys.concurrentRestrictions += Tags.limit(SingleThreadedRelease, 1)
  )

  val buildSettings: Seq[Setting[_]] = Seq(
    Keys.isSnapshot := Defaults.isSnapshot.value,
    Pgp.pgpPassphrase := Defaults.pgpPassphrase.value,
    releaseEarlyWith := Defaults.releaseEarlyWith.value
  )

  object PrivateKeys {
    // Note: code assumes that if it's not sonatype, it's Bintray.
    private val releaseEarlyIsSonatypeInternal: SettingKey[Boolean] =
      settingKey("Internal key that tells whether Sonatype is enabled.")
    val releaseEarlyIsSonatype: SettingKey[Boolean] =
      releaseEarlyIsSonatypeInternal in releaseEarly
  }

  // TODO(jvican): Rethink the proper scopes of all these keys.
  val projectSettings: Seq[Setting[_]] = Seq(
    Keys.isSnapshot := Defaults.isSnapshot.value,
    Keys.publishTo := Defaults.releaseEarlyPublishTo.value,
    releaseEarly := Defaults.releaseEarly.value,
    releaseEarlySyncToMaven := Defaults.releaseEarlySyncToMaven.value,
    releaseEarlyEnableSyncToMaven := Defaults.releaseEarlyEnableSyncToMaven.value,
    releaseEarlyValidatePom := Defaults.releaseEarlyValidatePom.value,
    releaseEarlyCheckRequirements := Defaults.releaseEarlyCheckRequirements.value,
    releaseEarlyBypassSnapshotCheck := Defaults.releaseEarlyBypassSnapshotChecks.value,
    releaseEarlyCheckSnapshotDependencies := Defaults.releaseEarlyCheckSnapshotDependencies.value,
    releaseEarlyPublish := Defaults.releaseEarlyPublish.value,
    releaseEarlyClose := Defaults.releaseEarlyClose.value,
    releaseEarlyProcess := Defaults.releaseEarlyProcess.value,
    PrivateKeys.releaseEarlyIsSonatype := Defaults.releaseEarlyIsSonatype.value
  ) ++ Defaults.saneDefaults

  object Defaults extends Helper {
    import ReleaseEarlyPlugin.{autoImport => ThisPluginKeys}

    /* Sbt bug: `Def.sequential` here produces 'Illegal dynamic reference' when
     * used inside `Def.taskDyn`. This is reported upstream, unclear if it can be fixed. */
    private val StableDef = new sbt.TaskSequential {}

    // Currently unused, but stays here for future features
    val dynVer: Def.Initialize[String] = Def.setting {
      import sbtdynver.{DynVer => OriginalDynVer}
      val customVersion = DynVer.dynverGitDescribeOutput.value.map { info =>
        // Use '+' for the distance because it is semver compatible
        val commitPart = info.commitSuffix.mkString("+", "+", "")
        info.ref.dropV.value + commitPart + info.dirtySuffix.value
      }
      customVersion.getOrElse(
        OriginalDynVer.fallback(DynVer.dynverCurrentDate.value))
    }

    // See https://github.com/dwijnand/sbt-dynver/issues/23.
    val isSnapshot: Def.Initialize[Boolean] = Def.setting {
      isDynVerSnapshot(DynVer.dynverGitDescribeOutput.value,
                       Keys.isSnapshot.value)
    }

    val pgpPassphrase: Def.Initialize[Option[Array[Char]]] = Def.setting {
      val currentPassword = Pgp.pgpPassphrase.value
      val logger = Keys.sLog.value
      if (currentPassword.isEmpty) {
        logger.debug(Feedback.LogFetchPgpCredentials)
        getPgpPassphraseFromEnvironment
      } else currentPassword
    }

    val releaseEarlyPublishTo: Def.Initialize[Option[sbt.Resolver]] = {
      Def.setting {
        // It is not necessary to use a dynamic setting here.
        val logger = Keys.sLog.value
        if (PrivateKeys.releaseEarlyIsSonatype.value) {
          // Sonatype requires instrumentation of publishTo to work.
          // Reference: https://github.com/xerial/sbt-sonatype#buildsbt
          val projectVersion = Keys.version.value
          if (isOldSnapshot(projectVersion))
            logger.error(Feedback.UnrecognisedPublisher)
          Some(sbt.Opts.resolver.sonatypeStaging)
        } else (Keys.publishTo in Bintray.bintray).value
      }
    }

    private final val SonatypeRealm = "Sonatype Nexus Repository Manager"
    private final val SonatypeHost = "oss.sonatype.org"
    val releaseEarlySonatypeCredentials
      : Def.Initialize[Task[Seq[sbt.Credentials]]] = {
      import sbt.{Credentials, DirectCredentials, FileCredentials}
      Def.task {
        val logger = Keys.streams.value.log
        val currentCredentials = Keys.credentials.value
        val existingSonatypeCredential = currentCredentials.find {
          case credentials: DirectCredentials =>
            credentials.realm == SonatypeRealm && credentials.host == SonatypeHost
          // The code in sbt-sonatype does not use file credentials, this is safe.
          case fileCredentials: FileCredentials => false
        }

        if (existingSonatypeCredential.isEmpty) {
          getSonatypeCredentials.orElse(getExtraSonatypeCredentials) match {
            case Some((user, passwd)) =>
              logger.debug(Feedback.LogAddSonatypeCredentials)
              val newCredentials =
                Credentials(SonatypeRealm, SonatypeHost, user, passwd)
              currentCredentials :+ newCredentials
            case _ => currentCredentials
          }
        } else currentCredentials
      }
    }

    val releaseEarlyEnableLocalReleases: Def.Initialize[Boolean] =
      Def.setting(false)

    val releaseEarlyWith: Def.Initialize[UnderlyingPublisher] =
      Def.setting(BintrayPublisher)

    val releaseEarlyInsideCI: Def.Initialize[Boolean] =
      Def.setting(sys.env.get("CI").isDefined)

    val releaseEarlyBypassSnapshotChecks: Def.Initialize[Boolean] =
      Def.setting(false)

    val releaseEarlyCheckSnapshotDependencies: Def.Initialize[Task[Unit]] = {
      Def.taskDyn {
        if (!ThisPluginKeys.releaseEarlyBypassSnapshotCheck.value) {
          val logger = Keys.streams.value.log
          logger.info(Feedback.logCheckSnapshots(Keys.name.value))
          val managedClasspath = (Keys.managedClasspath in sbt.Runtime).value
          val moduleIds = managedClasspath.flatMap(_.get(Keys.moduleID.key))
          // NOTE that we don't use sbt-release-early snapshot definition here.
          val snapshots =
            moduleIds.filter(m => m.isChanging || isOldSnapshot(m.revision))
          if (snapshots.nonEmpty)
            sys.error(Feedback.detectedSnapshotsDependencies(snapshots))
          else Def.task(())
        } else Def.task(())
      }
    }

    val releaseEarlyIsSonatype: Def.Initialize[Boolean] = Def.setting {
      val underlyingPublisher = ThisPluginKeys.releaseEarlyWith.value
      underlyingPublisher == SonatypePublisher
    }

    val releaseEarlyPublish: Def.Initialize[Task[Unit]] = Def.taskDyn {
      // If sonatype, always use `publishSigned` and ignore `publish`
      if (PrivateKeys.releaseEarlyIsSonatype.value) sonatypePublishAndRelease
      // If it's not sonatype, it's bintray... use signed for stable releases
      else if (!Keys.isSnapshot.value) Pgp.PgpKeys.publishSigned
      // Else, non-stable releases leverage Bintray's hijacked publish task
      else Keys.publish
    } dependsOn (Bintray.bintrayEnsureLicenses)

    private def sonatypePublishAndRelease: Def.Initialize[Task[Unit]] = {
      // Unfortunately, sbt-sonatype has a logical dependency between these tasks
      import Pgp.PgpKeys.publishSigned
      val wrapperTask = Def.taskDyn {
        val state = Keys.state.value
        sonatypeRelease(state)
          .dependsOn(publishSigned)
          .tag(SingleThreadedRelease)
      }
      wrapperTask
    }

    private def sonatypeRelease(state: sbt.State): Def.Initialize[Task[Unit]] = {
      // It looks like, for some reason, sonatype cannot be executed concurrently
      Def.task {
        val logger = Keys.streams.value.log
        val projectName = Keys.name.value
        logger.info(Feedback.logReleaseSonatype(projectName))
        val extracted = sbt.Project.extract(Keys.state.value)
        val profile = extracted.getOpt(SonatypeKeys.sonatypeStagingRepositoryProfile)
        profile.foreach(p => logger.info(s"Current sonatype profile: $p"))
        // Trick to make sure that 'sonatypeRelease' does not change the name
        import Sonatype.{sonatypeRelease => _}
        runCommandAndRemaining(s"sonatypeRelease")(state)
        ()
      }
    }

    /* For now, this task only execute `bintrayRelease`, `sonatypeRelease`
     * is tightly tied to `publishSigned` and cannot be easily decoupled.
     * Both have to be executed one after the other on and exclusively,
     * meaning that concurrency is not accepted. */
    val releaseEarlyClose: Def.Initialize[Task[Unit]] = Def.taskDyn {
      val state = Keys.state.value
      val logger = Keys.streams.value.log
      val projectName = Keys.name.value
      if (!PrivateKeys.releaseEarlyIsSonatype.value) {
        logger.info(Feedback.logReleaseBintray(projectName))
        Bintray.bintrayRelease
      } else Def.task(())
    }

    val releaseEarlyProcess: Def.Initialize[Seq[sbt.TaskKey[Unit]]] = {
      Def.setting(
        Seq(
          ThisPluginKeys.releaseEarlyCheckRequirements,
          DynVer.dynverAssertVersion,
          ThisPluginKeys.releaseEarlyValidatePom,
          ThisPluginKeys.releaseEarlyCheckSnapshotDependencies,
          ThisPluginKeys.releaseEarlyPublish,
          ThisPluginKeys.releaseEarlyClose,
          ThisPluginKeys.releaseEarlySyncToMaven
        )
      )
    }

    val releaseEarly: Def.Initialize[Task[Unit]] = Def.taskDyn {
      val logger = Keys.streams.value.log
      if (!ThisPluginKeys.releaseEarlyInsideCI.value &&
          !ThisPluginKeys.releaseEarlyEnableLocalReleases.value) {
        Def.task(sys.error(Feedback.OnlyCI))
      } else if (noArtifactToPublish.value) {
        val msg = Feedback.skipRelease(Keys.name.value)
        Def.task(logger.info(msg))
      } else {
        val steps = ThisPluginKeys.releaseEarlyProcess.value
        // Return task with unit value at the end
        val initializedSteps = steps.map(_.toTask)
        Def.taskDyn {
          logger.info(Feedback.logReleaseEarly(Keys.name.value))
          StableDef.sequential(initializedSteps, Def.task(()))
        }
      }
    }

    /** Validate POM files for synchronization with Maven Central.
      *
      * Items required:
      *   - Coordinates: groupId, artifactId, version.
      *   - Project: name, description, url.
      *   - License: name, url.
      *   - Developer information: name, email, organization, organizationUrl.
      *   - SCM: connection, developerConnection, url.
      *
      * From: https://blog.idrsolutions.com/2015/06/how-to-upload-your-java-artifact-to-maven-central/.
      */
    val releaseEarlyValidatePom: Def.Initialize[Task[Unit]] = {
      Def.taskDyn {
        // Don't run task on subprojects that don't publish
        if (Keys.publishArtifact.value) validatePomTask
        else Def.task(())
      }
    }

    val releaseEarlyCheckRequirements: Def.Initialize[Task[Unit]] = {
      Def.taskDyn {
        // Don't run task on subprojects that don't publish
        if (Keys.publishArtifact.value) checkRequirementsTask
        else Def.task(())
      }
    }

    val releaseEarlyEnableSyncToMaven: Def.Initialize[Boolean] =
      Def.setting(true)

    val releaseEarlySyncToMaven: Def.Initialize[Task[Unit]] = {
      Def.taskDyn {
        val logger = Keys.streams.value.log
        val projectName = Keys.name.value
        val bintrayIsEnabled = !PrivateKeys.releaseEarlyIsSonatype.value
        val mustSyncToMaven: Boolean = (
          bintrayIsEnabled &&
            ThisPluginKeys.releaseEarlyInsideCI.value &&
            ThisPluginKeys.releaseEarlyEnableSyncToMaven.value &&
            !Keys.isSnapshot.value
        )
        if (mustSyncToMaven) {
          Def.task {
            logger.info(Feedback.logSyncToMaven(projectName))
            bintray.BintrayKeys.bintraySyncMavenCentral.value
          }
        } else if (bintrayIsEnabled) {
          Def.task(logger.info(Feedback.skipSyncToMaven(projectName)))
        } else Def.task(())
      }
    }

    val saneDefaults: Seq[Setting[_]] = Seq(
      Bintray.bintrayOmitLicense := false,
      Bintray.bintrayReleaseOnPublish := false,
      Bintray.bintrayVcsUrl := {
        // This is necessary to create repos in bintray if they don't exist
        Bintray.bintrayVcsUrl.value
          .orElse(Keys.scmInfo.value.map(_.browseUrl.toString))
          .orElse {
            val url = Keys.pomExtra.value.\\("scm").\\("url").text
            if (url.nonEmpty) Some(url) else sys.error(Feedback.missingVcsUrl)
          }
      }
    )
  }
}

trait Helper {
  import sbt.Keys
  import sbt.State
  import ReleaseEarly.PrivateKeys

  def isOldSnapshot(version: String): Boolean =
    version.endsWith("-SNAPSHOT")

  def noArtifactToPublish: Def.Initialize[Task[Boolean]] = Def.task {
    import Keys.publishArtifact
    !(
      publishArtifact.value ||
        publishArtifact.in(sbt.Compile).value ||
        publishArtifact.in(sbt.Test).value
    )
  }

  def checkRequirementsTask: Def.Initialize[Task[Unit]] = Def.task {
    import scala.util.control.Exception.catching
    val logger = Keys.streams.value.log
    val projectName = Keys.name.value
    val useSonatype = PrivateKeys.releaseEarlyIsSonatype.value

    logger.info(Feedback.logCheckRequirements(projectName))

    val bintrayCredentials = {
      if (useSonatype) {
        logger.debug(Feedback.skipBintrayCredentialsCheck(projectName))
        None
      } else {
        catching(classOf[NoSuchElementException])
          .opt(bintray.BintrayKeys.bintrayEnsureCredentials.value)
      }
    }

    val sonatypeCredentials = {
      if (useSonatype) {
        getSonatypeCredentials.orElse {
          // Get extra credentials from optional environment variables
          val extraCredentials = getExtraSonatypeCredentials
          extraCredentials.foreach(persistExtraSonatypeCredentials)
          extraCredentials
        }
      } else {
        logger.debug(Feedback.skipBintrayCredentialsCheck(projectName))
        None
      }
    }

    // If not interactive, it means input has to come from environment
    val missingBintrayCredentials = !useSonatype && bintrayCredentials.isEmpty
    val missingSonatypeCredentials = (
      // True if sonatype is the underlying publisher
      useSonatype && (
        // Or if Bintray publishes a stable version under interactive mode
        !Keys.isSnapshot.value &&
          sonatypeCredentials.isEmpty &&
          !Keys.state.value.interactive
      )
    )

    val Checks = List(
      (missingBintrayCredentials, Feedback.missingBintrayCredentials),
      (missingSonatypeCredentials, Feedback.missingSonatypeCredentials)
    )

    val hasErrors = Checks.foldLeft(false) {
      case (hasError, (predicate, feedback)) =>
        if (predicate) { logger.error(feedback); true } else hasError
    }

    if (hasErrors) sys.error(Feedback.fixRequirementErrors)
  }

  def validatePomTask: Def.Initialize[Task[Unit]] = Def.task {
    val logger = Keys.streams.value.log
    logger.info(Feedback.logValidatePom(Keys.name.value))

    val Checks = List(
      (Keys.scmInfo.value.toList, "scm", Feedback.missingVcsUrl),
      (Keys.developers.value, "developers", Feedback.missingDevelopers),
      (Keys.licenses.value, "licenses", Feedback.forceValidLicense)
    )

    val pom = Keys.pomExtra.value
    val hasErrors = Checks.foldLeft(false) {
      case (hasError, (value, label, feedback)) =>
        if (value.isEmpty && missingNode(pom, label)) {
          logger.error(feedback)
          true
        } else hasError
    }

    // Ensure licenses before releasing
    val useBintray = !PrivateKeys.releaseEarlyIsSonatype.value
    if (useBintray) bintray.BintrayKeys.bintrayEnsureLicenses.value
    if (hasErrors) sys.error(Feedback.fixRequirementErrors)
  }

  def runCommandAndRemaining(command: String): State => State = { st: State =>
    import sbt.complete.Parser
    @annotation.tailrec
    def runCommand(command: String, state: State): State = {
      val nextState = Parser.parse(command, state.combinedParser) match {
        case Right(cmd) => cmd()
        case Left(msg)  => throw sys.error(s"Invalid programmatic input:\n$msg")
      }
      nextState.remainingCommands.toList match {
        case Nil => nextState
        case head :: tail =>
          runCommand(head, nextState.copy(remainingCommands = tail))
      }
    }
    runCommand(command, st.copy(remainingCommands = Nil))
      .copy(remainingCommands = st.remainingCommands)
  }

  import sbtdynver.GitDescribeOutput
  def isDynVerSnapshot(gitInfo: Option[GitDescribeOutput],
                       defaultValue: Boolean): Boolean = {
    val isStable = gitInfo.map { info =>
      info.ref.value.startsWith("v") &&
      (info.commitSuffix.distance <= 0 || info.commitSuffix.sha.isEmpty)
    }
    val isNewSnapshot =
      isStable.map(stable => !stable || defaultValue)
    // Return previous snapshot definition in case users has overridden version
    isNewSnapshot.getOrElse(defaultValue)
  }

  import scala.xml.NodeSeq
  protected def missingNode(pom: NodeSeq, label: String): Boolean =
    pom.\\(label).isEmpty

  /** Get extra sonatype credentials for those that dislike `SONA_*` and
    * system properties. The extra keys start with `SONATYPE` instead of `SONA`.
    */
  protected def getExtraSonatypeCredentials: Option[(String, String)] = {
    for {
      name <- sys.env.get("SONATYPE_USER")
      password <- sys.env.get("SONATYPE_PASSWORD")
    } yield (name, password)
  }

  private val PgpPasswordId = "PGP_PASSWORD"
  private val PgpPassId = "PGP_PASS"
  protected def getPgpPassphraseFromEnvironment: Option[Array[Char]] =
    sys.env.get(PgpPasswordId).orElse(sys.env.get(PgpPassId)).map(_.toArray)

  private val PropertyKeys = ("sona.user", "sona.pass")

  /** Persist extra sonatype credentials reusing the existing system properties.
    *
    * As we cannot access the sbt-bintray cache, we need to use the existing
    * infrastructure to support these extra environment variables.
    */
  protected def persistExtraSonatypeCredentials(
      credentials: (String, String)): Unit = {
    sys.props += PropertyKeys._1 -> credentials._1
    sys.props += PropertyKeys._2 -> credentials._2
  }

  /** Get Sonatype credentials from environment in the same way as sbt-bintray:
    *
    *   1. System properties.
    *   2. Environment variables.
    *
    * This code is copy-pasted from sbt-bintray and is slightly modified.
    */
  protected def getSonatypeCredentials: Option[(String, String)] = {
    val propsCredentials: Option[(String, String)] = {
      for {
        name <- sys.props.get(PropertyKeys._1)
        pass <- sys.props.get(PropertyKeys._2)
      } yield (name, pass)
    }
    propsCredentials.orElse {
      for {
        name <- sys.env.get("SONA_USER")
        pass <- sys.env.get("SONA_PASS")
      } yield (name, pass)
    }
  }
}
