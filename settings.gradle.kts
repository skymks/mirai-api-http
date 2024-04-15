pluginManagement {
    repositories {
//        if (System.getProperty("use.maven.local") == "true") {
//            mavenLocal()
//        }
        maven(url = "https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        maven(url = "https://plugins.gradle.org/m2/")
    }
}

rootProject.name = "mirai-api-http"

include(":mirai-api-http-spi")
include(":mirai-api-http")
