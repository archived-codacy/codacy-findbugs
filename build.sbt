import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}

name := "codacy-findbugs"

version := "1.0"

scalaVersion := "2.13.1"

libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % "1.2.0" withSources (),
  "com.codacy" %% "codacy-engine-scala-seed" % "3.1.0" withSources ()
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

mappings in Universal ++= {
  (resourceDirectory in Compile) map { resourceDir =>
    val src = resourceDir / "docs"
    val dest = "/docs"

    for {
      path <- src.allPaths.get if !path.isDirectory
    } yield path -> path.toString.replaceFirst(src.toString, dest)
  }
}.value

val dockerUser = "docker"
val dockerGroup = "docker"

daemonUser in Docker := dockerUser

daemonGroup in Docker := dockerGroup

dockerBaseImage := "rtfpessoa/ubuntu-jdk8"

dockerCommands := dockerCommands.value.flatMap {
  case cmd @ (Cmd("ADD", _)) =>
    List(
      Cmd("RUN", "adduser --uid 2004 --disabled-password --gecos \"\" docker"),
      cmd,
      Cmd("RUN", installAll),
      Cmd("RUN", "mv /opt/docker/docs /docs"),
      Cmd("RUN", s"chown -R $dockerUser:$dockerGroup /docs")
    )
  case other => List(other)
}
