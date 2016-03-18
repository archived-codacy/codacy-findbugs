package codacy

import codacy.dockerApi.DockerEngine
import codacy.findbugs.FindBugs

object Engine extends DockerEngine(FindBugs)
