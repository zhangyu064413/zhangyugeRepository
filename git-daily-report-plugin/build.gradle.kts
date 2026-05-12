plugins {
    id("org.jetbrains.intellij.platform") version "2.1.0"
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

group = "com.github.gitdailyreport"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2023.3")
        bundledPlugin("Git4Idea")
        instrumentationTools()
    }
}

kotlin {
    jvmToolchain(17)
}

tasks {
    patchPluginXml {
        sinceBuild.set("231.9011.34")
        // 不设置 until-build，保持向前兼容
    }

    buildSearchableOptions {
        enabled = false
    }
}
