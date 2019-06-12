package codacy.findbugs

import java.io.File
import java.nio.file.{Path, Paths}

import com.codacy.plugins.api.results.Result.{FileError, Issue}
import com.codacy.plugins.api.{ErrorMessage, Options, Source}
import com.codacy.plugins.api.results.{Pattern, Result, Tool}
import com.codacy.tools.scala.seed.traits.Builder
import com.codacy.tools.scala.seed.utils.{CommandRunner, FileHelper}
import com.codacy.tools.scala.seed.utils.ToolHelper._

import scala.util.{Failure, Properties, Success, Try}
import scala.xml.{Node, XML}

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

  def apply(source: Source.Directory,
            configuration: Option[List[Pattern.Definition]],
            files: Option[Set[Source.File]],
            options: Map[Options.Key, Options.Value])(implicit specification: Tool.Specification): Try[List[Result]] = {
    val path = Paths.get(source.path)
    BuilderFactory(path) match {
      case Some(builder) =>
        val completeConf = configuration.withDefaultParameters
        builder.build(path) match {
          case Success(_) => processTool(path, completeConf, files, builder)
          case Failure(throwable) => Failure(throwable)
        }
      case _ => Failure(new Exception("Could not support project compilation."))
    }
  }

  private lazy val configFilenames = Set("findbugs.xml", "findbugs-includes.xml")
  private lazy val excludeFilenames = Set("findbugs-excludes.xml")

  private lazy val defaultCmd = List("/bin/bash", "findbugs-cli.sh", "-xml:withMessages", "-output", "/tmp/output.xml")

  private[this] def toolCommand(path: Path, conf: Option[List[Pattern.Definition]], builder: Builder): (List[String], Array[File]) = {

    lazy val nativeConf = FileHelper.findConfigurationFile(path, configFilenames).map(_.toString)
    lazy val excludeFile = FileHelper.findConfigurationFile(path, excludeFilenames).map(_.toString)

    val rulesParams = conf.map(patternIncludeXML).map(rules => List("-include", rules)).getOrElse {
      (nativeConf.map(List("-include", _)) ++ excludeFile.map(List("-exclude", _))).flatten
    }

    val sourceDirs = collectTargets(path, builder)
    val targetDirs = sourceDirs.flatMap(builder.targetOfDirectory).toSeq
    (defaultCmd ++ rulesParams ++ targetDirs, sourceDirs)
  }

  private[this] def processTool(path: Path,
                                conf: Option[List[Pattern.Definition]],
                                files: Option[Set[Source.File]],
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
      start <- elem.attribute("start")
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

  private[this] def isFileEnabled(path: String, filesOpt: Option[Set[Source.File]]): Boolean = {
    filesOpt.fold(true) { files => files.exists( filePath => Paths.get(filePath.path).toAbsolutePath == path) }
  }

  private[this] def resultsFromBugInstances(bugs: Seq[BugInstance],
                                            sourceDirs: Array[File],
                                            files: Option[Set[Source.File]],
                                            builder: Builder): Seq[Result] = {
    val sourceDirectories = sourceDirs.map { dir =>
      val components = Seq(dir.getAbsolutePath) ++ builder.pathComponents
      val dirPath = components.mkString(File.separator)
      new SourceDirectory(new File(dirPath))
    }

    bugs.flatMap { bug =>
      val foundOriginDirectories = sourceDirectories.filter(_.subdirectoryExists(bug.occurence.components))

      val results: Seq[Result] = foundOriginDirectories.collect {
        case directory if foundOriginDirectories.length == 1 && isFileEnabled(sourceFileName(directory, bug), files) =>
          val filename = sourceFileName(directory, bug)
          Result.Issue(Source.File(filename),
            Result.Message(bug.message),
            Pattern.Id(bug.name),
            Source.Line(bug.occurence.lineno))
        case directory if foundOriginDirectories.length > 1 && isFileEnabled(sourceFileName(directory, bug), files) =>
          val filename = sourceFileName(directory, bug)
          Result.FileError(Source.File(filename),
            Option(ErrorMessage("File duplicated in multiple directories.")))

      }
      results
    }
  }

  private[this] def parseOutputFile(): Seq[BugInstance] = {
    val xmlOutput = XML.loadFile("/tmp/output.xml")
    val bugInstances = xmlOutput \ "BugInstance"
    bugInstances.flatMap { bugInstance =>
      // If the there is a SourceLine under the BugInstance, then that is
      // the line that will reported. The only issue though is only the first
      // occurence is emitted, even though there can be many more, that's why we're
      // using .head down here.
      val sourceLineOccurences = bugInstance \ "SourceLine"
      val patternName = bugInstance \@ "type"
      val message = (bugInstance \ "LongMessage").head.text
      val occurences = (if (sourceLineOccurences.nonEmpty) {
        elementPathAndLine(sourceLineOccurences.head)
      } else {
        val methodSourceLine = bugInstance \ "Method"
        if (methodSourceLine.nonEmpty) {
          elementPathAndLine(methodSourceLine.head)
        } else {
          Option.empty
        }
      }) getOrElse Seq()
      occurences.map(new BugInstance(patternName, message, _))
    }
  }

  private[this] def patternIncludeXML(conf: List[Pattern.Definition]): String = {
    val xmlLiteral = <FindBugsFilter>
      {conf.map(pattern =>
        <Match>
          <Bug pattern={pattern.patternId.value}/>
        </Match>
      )}
    </FindBugsFilter>.toString
    val tmp = FileHelper.createTmpFile(xmlLiteral, "findbugs", "")
    tmp.toAbsolutePath.toString
  }

  private[this] def collectTargets(path: Path, builder: Builder): Array[File] = {
    // Get the directories that can be projects (including subprojects and the current directory).
    val directories = path.toFile.listFiles.filter(_.isDirectory) ++ Seq(path.toFile)
    directories.filter(directory =>
      builder.targetOfDirectory(directory).fold(false)(target => new File(target).exists))
  }
}
