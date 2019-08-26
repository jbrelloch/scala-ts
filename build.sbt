import sbt.Keys._

lazy val root = (project in file(".")).
  settings(Seq(
    name := "scala-ts",
    organization := "com.github.miloszpp",
    mainClass in (Compile, run) := Some("com.mpc.scalats.Main"),
    sbtPlugin := true,
    scalaVersion := "2.12.7",
    crossScalaVersions := Seq("2.10.7", scalaVersion.value),
    publishArtifact in (Compile, packageDoc) := false,
    sbtVersion in pluginCrossBuild := {
      scalaBinaryVersion.value match {
        case "2.10" => "0.13.16"
        case "2.12" => "1.1.0"
      }
    }) ++ Scalac.settings)

libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.scalatest" %% "scalatest" % "3.0.8" % "test"
)
