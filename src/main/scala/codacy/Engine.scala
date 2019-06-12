package codacy

import codacy.findbugs.FindBugs
import com.codacy.tools.scala.seed.DockerEngine

object Engine extends DockerEngine(FindBugs)()
