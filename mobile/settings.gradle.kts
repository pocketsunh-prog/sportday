pluginManagement {
    repositories {
        maven {
            url = uri("http://maven.aliyun.com/nexus/content/groups/public/")
            isAllowInsecureProtocol = true
        }
        maven {
            url = uri("http://maven.aliyun.com/nexus/content/repositories/google")
            isAllowInsecureProtocol = true
        }
        maven {
            url = uri("http://maven.aliyun.com/nexus/content/repositories/gradle-plugin")
            isAllowInsecureProtocol = true
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("http://maven.aliyun.com/nexus/content/groups/public/")
            isAllowInsecureProtocol = true
        }
        maven {
            url = uri("http://maven.aliyun.com/nexus/content/repositories/google")
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "SportDayMobile"
include(":app")
