val scala3Version = "3.3.6"
val pekkoVersion = "1.0.2"
val pekkoHttpVersion = "1.2.0"
val pekkoGrpcVersion = "1.0.2"
val cequenceVersion = "1.0.0"

lazy val root = project
  .in(file("."))
  .enablePlugins(PekkoGrpcPlugin, JavaAppPackaging)
  .settings(
    name := "multiagent-system-pekko",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    
    // Pekko gRPC settings
    pekkoGrpcGeneratedLanguages := Seq(PekkoGrpc.Scala),
    pekkoGrpcCodeGeneratorSettings := pekkoGrpcCodeGeneratorSettings.value
      .filterNot(_ == "flat_package"),
    
    libraryDependencies ++= Seq(
      // Pekko Core
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
      
      // Pekko HTTP & gRPC
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      // "org.apache.pekko" %% "pekko-http-support" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-grpc-runtime" % pekkoGrpcVersion,
      
      // LLM Clients
      "io.cequence" %% "openai-scala-client" % "1.0.0",
      
      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.14",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      
      // Config
      "com.typesafe" % "config" % "1.4.3",
      
      // JSON Serialization
      "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
      
      // Testing
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test,
      "org.scalatest" %% "scalatest" % "3.2.17" % Test,
      "org.scalatestplus" %% "mockito-4-11" % "3.2.17.0" % Test
    ),
    
    scalacOptions ++= Seq(
      "-encoding", "UTF-8",
      "-feature",
      "-unchecked",
      "-deprecation",
      "-language:postfixOps",
      "-language:implicitConversions"
    ),
    
    // Resolve version conflicts
    dependencyOverrides ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
      "org.apache.pekko" %% "pekko-protobuf-v3" % pekkoVersion
    ),
    
    fork := true,
    run / javaOptions ++= Seq(
      "-Xmx2G",
      "-Dconfig.file=src/main/resources/application.conf"
    )
  )