plugins {
  kotlin("jvm") version "2.2.21"
  id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.xiaokeer.idea"
version = "1.0.0"

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

  intellijPlatform {
    intellijIdeaUltimate("2026.1.2")
    bundledPlugin("JavaScript")
  }
}

kotlin {
  jvmToolchain(17)
}

intellijPlatform {
  pluginConfiguration {
    ideaVersion {
      sinceBuild = "261"
      untilBuild = "261.*"
    }
  }
}

tasks {
  instrumentCode {
    enabled = false
  }

  buildSearchableOptions {
    enabled = false
  }
}
