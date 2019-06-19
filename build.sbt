import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}

name := """codacy-engine-findbugs"""

version := "1.0"

val languageVersion = "2.12.7"

scalaVersion := languageVersion

resolvers ++= Seq(
  "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/releases"
)

libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % "1.0.5" withSources(),
  "com.codacy" %% "codacy-engine-scala-seed" % "3.0.9" withSources()
)

enablePlugins(JavaAppPackaging)

enablePlugins(DockerPlugin)

version in Docker := "1.0"

val findBugsVersion = "3.0.1"

val installAll =
  s"""echo "deb http://dl.bintray.com/sbt/debian /" | tee -a /etc/apt/sources.list.d/sbt.list &&
     |apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 642AC823 &&
     |apt-get -y update &&
     |apt-get -y install maven &&
     |apt-get -y install sbt &&
     |wget http://netix.dl.sourceforge.net/project/findbugs/findbugs/$findBugsVersion/findbugs-$findBugsVersion.tar.gz &&
     |mkdir /opt/docker/findbugs &&
     |gzip -dc findbugs-$findBugsVersion.tar.gz | tar -xf - -C /opt/docker/findbugs &&
     |echo "java -jar /opt/docker/findbugs/findbugs-$findBugsVersion/lib/findbugs.jar \\$$@" > /opt/docker/findbugs-cli.sh &&
     |chmod +x /opt/docker/findbugs-cli.sh""".stripMargin.replaceAll(System.lineSeparator(), " ")

mappings.in(Universal) ++= resourceDirectory
  .in(Compile)
  .map { resourceDir: File =>
    val src = resourceDir / "docs"
    val dest = "/docs"

    (for {
      path <- better.files.File(src.toPath).listRecursively()
      if !path.isDirectory
    } yield path.toJava -> path.toString.replaceFirst(src.toString, dest)).toSeq
  }
  .value

val dockerUser = "docker"
val dockerGroup = "docker"

daemonUser in Docker := dockerUser

daemonGroup in Docker := dockerGroup

dockerBaseImage := "rtfpessoa/ubuntu-jdk8"

dockerCommands := dockerCommands.value.flatMap {
  case cmd@(Cmd("ADD", _)) => List(
    Cmd("RUN", "adduser --uid 2004 --disabled-password --gecos \"\" docker"),
    cmd,
    Cmd("RUN", installAll),
    Cmd("RUN", "mv /opt/docker/docs /docs"),
    ExecCmd("RUN", Seq("chown", "-R", s"$dockerUser:$dockerGroup", "/docs"): _*)
  )
  case other => List(other)
}
