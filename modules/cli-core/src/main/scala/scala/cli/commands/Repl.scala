package scala.cli.commands

import caseapp._
import scala.build.{Build, Inputs, Logger, Os, ReplArtifacts}
import scala.build.internal.{Constants, Runner}

object Repl extends ScalaCommand[ReplOptions] {
  override def group = "Main"
  override def names = List(
    List("console"),
    List("repl")
  )
  override def sharedOptions(options: ReplOptions) = Some(options.shared)
  def run(options: ReplOptions, args: RemainingArgs): Unit = {

    val inputs = options.shared.inputsOrExit(args)

    val initialBuildOptions = options.buildOptions
    val bloopRifleConfig = options.shared.bloopRifleConfig()
    val logger = options.shared.logger

    val directories = options.shared.directories.directories

    val build = Build.build(inputs, initialBuildOptions, bloopRifleConfig, logger)

    val successfulBuild = build.successfulOpt.getOrElse {
      System.err.println("Compilation failed")
      sys.exit(1)
    }

    def maybeRunRepl(build: Build, allowExit: Boolean): Unit =
      build match {
        case s: Build.Successful =>
          runRepl(s, directories, logger, allowExit = allowExit)
        case f: Build.Failed =>
          System.err.println("Compilation failed")
          if (allowExit)
            sys.exit(1)
      }

    if (options.watch.watch) {
      val watcher = Build.watch(inputs, initialBuildOptions, bloopRifleConfig, logger, postAction = () => WatchUtil.printWatchMessage()) { build =>
        maybeRunRepl(build, allowExit = false)
      }
      try WatchUtil.waitForCtrlC()
      finally watcher.dispose()
    } else {
      val build = Build.build(inputs, initialBuildOptions, bloopRifleConfig, logger)
      maybeRunRepl(build, allowExit = true)
    }
  }

  private def runRepl(
    build: Build.Successful,
    directories: scala.build.Directories,
    logger: Logger,
    allowExit: Boolean
  ): Unit = {

    val replArtifacts = ReplArtifacts(
      build.artifacts.params,
      build.options.replOptions.ammoniteVersionOpt.getOrElse(Constants.ammoniteVersion),
      build.artifacts.dependencies,
      logger,
      directories
    )

    // TODO Warn if some entries of build.artifacts.classPath were evicted in replArtifacts.replClassPath
    //      (should be artifacts whose version was bumped by Ammonite).

    // TODO Find the common namespace of all user classes, and import it all in the Ammonite session.

    // TODO Allow to disable printing the welcome banner and the "Loading..." message in Ammonite.

    // FIXME Seems Ammonite isn't fully fine with directories as class path (these are passed to the interactive
    //       compiler for completion, but not to the main compiler for actual compilation).

    lazy val rootClasses = os.list(build.output)
      .filter(_.last.endsWith(".class"))
      .filter(os.isFile(_)) // just in case
      .map(_.last.stripSuffix(".class"))
      .sorted
    if (rootClasses.nonEmpty)
      logger.message(s"Warning: found classes defined in the root package (${rootClasses.mkString(", ")}). These will not be accessible from the REPL.")
    Runner.run(
      build.options.javaCommand(),
      build.options.javaOptions.javaOpts,
      build.output.toIO +: replArtifacts.replClassPath.map(_.toFile),
      ammoniteMainClass,
      Nil,
      logger,
      allowExecve = allowExit
    )
  }

  private def ammoniteMainClass: String =
    "ammonite.Main"
}