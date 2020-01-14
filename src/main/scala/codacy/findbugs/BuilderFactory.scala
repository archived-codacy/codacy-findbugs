package codacy.findbugs

import java.nio.file.Path

import com.codacy.tools.scala.seed.traits.{Builder, MavenBuilder}

object BuilderFactory {

  lazy val knownBuilders = Seq(
    MavenBuilder
    // FindBugs behaves funny when running over Scala code, returning
    // class names as seen in the tracebacks rather than proper files.
    // For now we just disable it.
    //SBTBuilder
  )

  def apply(path: Path): Option[Builder] = {
    val builders = knownBuilders.filter { case builder => builder.supported(path) }
    builders.headOption
  }

}
