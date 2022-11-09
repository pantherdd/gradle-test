import com.github.spotbugs.snom.SpotBugsTask
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RepositoryBuilder
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files

buildscript {
    dependencies {
        classpath("org.eclipse.jgit:org.eclipse.jgit:6.3.0.202209071007-r")
    }
}

// --- Gradle infrastructure setup: Gradle distribution to use (run *twice* after modifications) ---
tasks.wrapper {
    // See: https://gradle.org/releases/
    gradleVersion = "7.5.1"
    distributionType = Wrapper.DistributionType.ALL
}
// --- ========== ---

plugins {
    `java-library`
    `maven-publish`
    checkstyle
    pmd
    id("com.github.spotbugs").version("5.0.13")
    jacoco
}

group = "io.github.pantherdd"
version = "0.0.1"

val productVendor = "Denes Daniel (https://github.com/pantherdd)"
val productTitle = "Gradle test project"
val productDescription = "Example Gradle project that builds automatically on GitHub."
val productUrl = "https://github.com/pantherdd/gradle-test"

val supportedJavaVersions = sortedSetOf(JavaVersion.VERSION_1_8, JavaVersion.VERSION_11, JavaVersion.VERSION_17)
val sourceJavaVersion = supportedJavaVersions.minOf { it }
val toolsJavaVersion = JavaVersion.VERSION_11

fun toolchainSpec(javaVersion: JavaVersion) = { toolchain: JavaToolchainSpec ->
    toolchain.vendor.set(JvmVendorSpec.AZUL)
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion.majorVersion))
}

fun <T> queryGit(query: (Git) -> T): T =
    RepositoryBuilder().setGitDir(file(".git")).setMustExist(true).build().use { query(Git(it)) }

data class GitStatus(val commit: String, val clean: Boolean) {
    fun asCommit() = if (clean) commit else "${commit}-dirty"
}
val gitStatus = try {
    queryGit { git ->
        val headOid = checkNotNull(git.repository.resolve(Constants.HEAD)) { "Could not resolve HEAD commit" }
        val status = git.status().call()!!
        GitStatus(headOid.name!!, status.isClean)
    }
} catch (ex: RepositoryNotFoundException) {
    null
}

val manifestAttributes by lazy {
    val compileTask = tasks.compileJava.get()
    mapOf(
        "Automatic-Module-Name" to "${project.group}.${project.name}".replace("-", ""),
        "Implementation-Title" to productTitle,
        "Implementation-Version" to project.version,
        "Implementation-Vendor" to productVendor,
        "Build-Jdk" to compileTask.javaCompiler.get().metadata.run { "${javaRuntimeVersion} (${vendor})" },
        "Build-Jdk-Spec" to compileTask.options.release.get(),
        "Build-Scm-Commit" to (gitStatus?.asCommit() ?: "unknown"),
        "Build-Scm-Url" to "${productUrl}/tree/v${project.version}",
    )
}

java {
    toolchain(toolchainSpec(toolsJavaVersion))
    // Not really used by Gradle, only added for better IDE integration
    sourceCompatibility = sourceJavaVersion

    withJavadocJar()
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    val spotbugsAnnotations = spotbugs.toolVersion.map { "com.github.spotbugs:spotbugs-annotations:${it}" }
    compileOnly(spotbugsAnnotations)
    testCompileOnly(spotbugsAnnotations)

    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    testImplementation("org.assertj:assertj-core:3.23.1")
}

tasks.withType<JavaCompile> {
    options.release.set(sourceJavaVersion.majorVersion.toInt())
}

tasks.javadoc {
    val fullOptions = options.windowTitle(productTitle).apply {
        encoding = Charsets.UTF_8.name()
        memberLevel = JavadocMemberLevel.PUBLIC
        // See: https://github.com/gradle/gradle/issues/18274
        addStringOption("-release", sourceJavaVersion.majorVersion)
    }

    // Javadoc in Java 9+ requires this `linkoffline` workaround to be able to link to Java 8 docs
    // In case of a package overlap (e.g. `javax.annotation`), the last offline link takes precedence
    // To have control over the situation, we have to use offline links for every dependency
    doFirst {
        val linksDir = temporaryDir.resolve("links")
        delete(linksDir.toPath())
        Files.createDirectories(linksDir.toPath())

        data class OfflineLink(val dir: File, val uri: URI)
        val descriptorFileNames = listOf("element-list", "package-list")

        // For built-in dependencies, download Javadoc descriptors manually from the URI
        val linksForBuiltIns = listOf(
            OfflineLink(linksDir.resolve("java"),
                uri("https://docs.oracle.com/javase/${sourceJavaVersion.majorVersion}/docs/api/")),
        ).onEach { offlineLink ->
            val javadocDescriptor = descriptorFileNames.mapNotNull { fileName ->
                with(offlineLink.uri.resolve(fileName).toURL().openConnection() as HttpURLConnection) {
                    instanceFollowRedirects = false
                    inputStream.use { content ->
                        responseCode.takeIf { it == 200 }?.let {
                            Files.createDirectories(offlineLink.dir.toPath())
                            offlineLink.dir.resolve(fileName).apply {
                                Files.copy(content, toPath())
                            }
                        }
                    }
                }
            }.firstOrNull()
            checkNotNull(javadocDescriptor) { "Could not download Javadoc descriptor from ${offlineLink.uri}" }
        }

        // For external modules, extract Javadoc descriptors from the module's Javadoc JAR
        val linksForModules = run {
            val compileComponents = configurations.compileClasspath.get().incoming.resolutionResult.allComponents
            val compileJavadocs = dependencies.createArtifactResolutionQuery()
                .forComponents(compileComponents.map { it.id })
                .withArtifacts(JvmLibrary::class, JavadocArtifact::class)
                .execute()
            compileJavadocs.resolvedComponents.mapNotNull { component ->
                val javadocArtifact = component.getArtifacts(JavadocArtifact::class).single() as ResolvedArtifactResult
                val javadocJarTree = zipTree(javadocArtifact.file)
                val javadocDescriptor = descriptorFileNames.mapNotNull { fileName ->
                    javadocJarTree.matching { include(fileName) }.singleOrNull()
                }.firstOrNull()
                javadocDescriptor?.let { file ->
                    val id = component.id as ModuleComponentIdentifier
                    OfflineLink(file.parentFile, uri("https://javadoc.io/doc/${id.group}/${id.module}/${id.version}/"))
                }
            }
        }

        // Add offline links
        linksForBuiltIns.plus(linksForModules).forEach { offlineLink ->
            fullOptions.linksOffline(offlineLink.uri.toString(), offlineLink.dir.path)
        }
        // Generate correct deep links to Java 8 methods (HTML5: `#toString()`, HTML4: `#toString--`)
        fullOptions.addBooleanOption("html4", true)
    }
}

tasks.withType<Jar> {
    manifest.attributes(manifestAttributes)
}

checkstyle {
    toolVersion = "10.4"
    maxErrors = 0
    maxWarnings = 0
}

pmd {
    ruleSets = listOf("category/java/bestpractices.xml")
}

tasks.withType<SpotBugsTask> {
    reports.register("html")
    reports.register("xml")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
supportedJavaVersions.forEach { javaVersion ->
    val testTask =
        if (javaVersion == sourceJavaVersion) tasks.test.get()
        else tasks.create<Test>("testOnJava${javaVersion.majorVersion}")
    with(testTask) {
        group = JavaBasePlugin.VERIFICATION_GROUP
        description = "Runs the test suite on Java ${javaVersion.majorVersion}."
        javaLauncher.set(javaToolchains.launcherFor(toolchainSpec(javaVersion)))

        configure<JacocoTaskExtension> {
            sessionId = "${project.name}-${name}"
        }
        finalizedBy(tasks.jacocoTestReport)
    }
    tasks.jacocoTestReport {
        executionData(testTask)
    }
    tasks.jacocoTestCoverageVerification {
        executionData(testTask)
    }
}
tasks.check {
    dependsOn(tasks.withType<Test>())
}

tasks.jacocoTestReport {
    reports.html.required.set(true)
    reports.xml.required.set(true)
}
tasks.jacocoTestCoverageVerification {
    mustRunAfter(tasks.jacocoTestReport)
    violationRules.rule {
        limit {
            minimum = "1.000".toBigDecimal()
        }
    }
}
tasks.check {
    dependsOn(tasks.withType<JacocoReport>(), tasks.withType<JacocoCoverageVerification>())
}

publishing {
    repositories.maven(layout.buildDirectory.dir("local-publish"))
    publications.create<MavenPublication>("product") {
        from(components.getByName("java"))
        pom {
            name.set(productTitle)
            description.set(productDescription)
            url.set(productUrl)
        }
    }
}

tasks.publish {
    dependsOn(tasks.build)
    doFirst {
        val localStatus = checkNotNull(gitStatus) { "Could not query local Git repository" }
        val releaseTag = "v${project.version}"
        val releaseCommit = queryGit { git ->
            val remoteTags = git.lsRemote().setRemote("${productUrl}.git").setTags(true).callAsMap()
            val releaseRef = checkNotNull(remoteTags.get("refs/tags/${releaseTag}")) {
                "Release tag \"${releaseTag}\" does not exist in root repository"
            }
            val releaseOid = checkNotNull(releaseRef.run { peeledObjectId ?: objectId }) {
                "Could not resolve release tag \"${releaseTag}\""
            }
            releaseOid.name!!
        }
        check(localStatus.commit == releaseCommit) {
            "Local Git repository must have release tag \"${releaseTag}\" checked out for publication"
        }
        check(localStatus.clean) {
            "Local Git repository must be in a clean state for publication"
        }
    }
}
