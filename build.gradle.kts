import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.java")
        bundledPlugin("JUnit")
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.github")
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    // A partir da 2025.3 só existe a IntelliJ IDEA unificada: o verifyPlugin confere as APIs nas versões novas.
    pluginVerification {
        failureLevel = listOf(VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS, VerifyPluginTask.FailureLevel.INVALID_PLUGIN)
        ides {
            providers.gradleProperty("verifierIdeVersions").get().split(',').map(String::trim).forEach {
                create(IntelliJPlatformType.IntellijIdea, it)
            }
        }
    }
}

tasks.named("buildPlugin") {
    dependsOn(tasks.test)
}

// A compilação dos testes ficava em dia mesmo com assinaturas novas no código principal (parâmetros com valor
// padrão, construtores), e os testes quebravam com NoSuchMethodError: as classes principais entram pelo conteúdo.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    inputs.files(sourceSets.main.get().output.classesDirs)
        .withNormalizer(ClasspathNormalizer::class.java)
        .withPropertyName("mainClassesContent")
    incremental = false
}
