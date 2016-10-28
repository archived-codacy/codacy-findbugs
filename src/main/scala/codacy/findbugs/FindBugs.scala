package codacy.findbugs

import java.io.File
import java.nio.file.Path

import codacy.dockerApi._
import codacy.dockerApi.utils.{CommandRunner, FileHelper, ToolHelper}

import scala.util.{Failure, Properties, Success, Try}
import scala.xml.{Node, XML}
import codacy.dockerApi.traits._

private class Occurence(val lineno: Integer, val path: String) {
  lazy val packageName = path.split(File.separatorChar).headOption.getOrElse("")
  lazy val components = path.split(File.separatorChar)
}

private class BugInstance(val name: String, val message: String, val occurence: Occurence)

private class SourceDirectory(val absolutePath: File) {

  lazy val absoluteStringPath = absolutePath.getAbsolutePath

  def subdirectoryExists(components: Seq[String]): Boolean = {
    val pathComponents = Seq(absoluteStringPath) ++ components
    new File(pathComponents.mkString(File.separator)).exists()
  }
}

object FindBugs extends Tool {

  override def apply(path: Path, conf: Option[List[PatternDef]], files: Option[Set[Path]])(implicit spec: Spec): Try[List[Result]] = {
    BuilderFactory(path) match {
      case Some(builder) =>
        val completeConf = ToolHelper.getPatternsToLint(conf)
        builder.build(path) match {
          case Success(value) => processTool(path, completeConf, files, builder)
          case Failure(throwable) => Failure(throwable)
        }
      case _ => Failure(new Exception("Could not support project compilation."))
    }
  }

  private lazy val configFilenames = Set("findbugs.xml")
  private lazy val defaultCmd = List("/bin/bash", "findbugs-cli.sh", "-xml:withMessages", "-output", "/tmp/output.xml")

  private[this] def toolCommand(path: Path, conf: Option[List[PatternDef]], builder: Builder) = {

    lazy val nativeConf = configFilenames.map( cfgFile => Try(new better.files.File(path) / cfgFile))
        .collectFirst{ case Success(file) if file.isRegularFile => file.toJava.getAbsolutePath }

    val rulesParams = conf.map( patternIncludeXML ).orElse( nativeConf )
      .map( rules => List("-include", rules) ).getOrElse(List.empty)

    val sourceDirs = collectTargets(path, builder)
    val targetDirs = sourceDirs.flatMap(builder.targetOfDirectory).toSeq
    (defaultCmd ++ rulesParams ++ targetDirs, sourceDirs)
  }

  private[this] def processTool(path: Path,
                                conf: Option[List[PatternDef]],
                                files: Option[Set[Path]],
                                builder: Builder): Try[List[Result]] = {

    val (command, sourceDirs) = toolCommand(path, conf, builder)
    CommandRunner.exec(command) match {
      case Left(throwable) => Failure(throwable)
      case Right(output) if output.exitCode != 0 =>
        Failure(new Exception(
          s"""Can't execute tool
             |stdout: ${output.stdout.mkString(Properties.lineSeparator)}
             |stderr: ${output.stderr.mkString(Properties.lineSeparator)}
           """.stripMargin))

      case Right(_) =>
        Try {
          val bugs = parseOutputFile()
          resultsFromBugInstances(bugs, sourceDirs, files, builder).toList
        }
    }
  }

  private[this] def elementPathAndLine(elem: Node): Option[Seq[Occurence]] = {
    for {
      start      <- elem.attribute("start")
      sourcepath <- elem.attribute("sourcepath")
    } yield {
      (start zip sourcepath).map { case (startNode, sourcePathNode) =>
        new Occurence(startNode.text.toInt, sourcePathNode.text)
      }
    }
  }

  private[this] def sourceFileName(directory: SourceDirectory, bug: BugInstance) = {
    Seq(directory.absoluteStringPath, bug.occurence.path).mkString(File.separator)
  }

  private[this] def isFileEnabled(path: String, files: Option[Set[Path]]): Boolean = {
    files.fold(true) { case files => files.exists(_.toAbsolutePath.toFile.getAbsolutePath == path) }
  }

  private[this] def resultsFromBugInstances(bugs: Seq[BugInstance],
                                            sourceDirs: Array[File],
                                            files: Option[Set[Path]],
                                            builder: Builder): Seq[Result] = {
    val sourceDirectories = sourceDirs.map { case dir =>
      val components = Seq(dir.getAbsolutePath) ++ builder.pathComponents
      val dirPath = components.mkString(File.separator)
      new SourceDirectory(new File(dirPath))
    }

    bugs.flatMap { case bug =>
      val foundOriginDirectories = sourceDirectories.filter(_.subdirectoryExists(bug.occurence.components))

      val results: Seq[Result] = foundOriginDirectories.collect {
        case directory if foundOriginDirectories.size == 1 && isFileEnabled(sourceFileName(directory, bug), files) =>
          val filename = sourceFileName(directory, bug)
          Issue(SourcePath(filename),
                ResultMessage(bug.message),
                PatternId(bug.name),
                ResultLine(bug.occurence.lineno))
        case directory if foundOriginDirectories.size > 1 && isFileEnabled(sourceFileName(directory, bug), files) =>
          val filename = sourceFileName(directory, bug)
          FileError(SourcePath(filename),
                    Option(ErrorMessage("File duplicated in multiple directories.")))

      }
      results
    }
  }

  private[this] def parseOutputFile(): Seq[BugInstance] = {
    val xmlOutput = XML.loadFile("/tmp/output.xml")
    val bugInstances = xmlOutput \ "BugInstance"
    bugInstances.flatMap { case bugInstance =>
      // If the there is a SourceLine under the BugInstance, then that is
      // the line that will reported. The only issue though is only the first
      // occurence is emitted, even though there can be many more, that's why we're
      // using .head down here.
      val sourceLineOccurences = bugInstance \ "SourceLine"
      val patternName = bugInstance \@ "type"
      val message = (bugInstance \ "LongMessage").head.text
      val occurences = (sourceLineOccurences.nonEmpty match {
        case true => elementPathAndLine(sourceLineOccurences.head)
        case false =>
          val methodSourceLine = bugInstance \ "Method"
          methodSourceLine.nonEmpty match {
            case true => elementPathAndLine(methodSourceLine.head)
            case false => Option.empty
          }
      }) getOrElse Seq()
      occurences.map(new BugInstance(patternName, message, _))
    }
  }

  private[this] def patternIncludeXML(conf: List[PatternDef]): String = {
    val xmlLiteral = <FindBugsFilter>{
      conf.map( pattern =>
        <Match>
          <Bug pattern={pattern.patternId.value}/>
        </Match>
      )
    }
    </FindBugsFilter>.toString
    val tmp = FileHelper.createTmpFile(xmlLiteral, "findbugs", "")
    tmp.toAbsolutePath.toString
  }

  private[this] def collectTargets(path: Path, builder: Builder): Array[File] = {
    // Get the directories that can be projects (including subprojects and the current directory).
    val directories = path.toFile.listFiles.filter(_.isDirectory) ++ Seq(path.toFile)
    directories.filter {
      case directory =>
        builder.targetOfDirectory(directory).fold(false) {
          case target => new File(target).exists
        }

    }
  }
}
