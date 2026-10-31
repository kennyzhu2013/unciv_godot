import com.unciv.build.BuildConfig
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

// Java 21+ deprecates dynamic agent loading: https://openjdk.org/jeps/451
val mockitoAgent = configurations.create("mockitoAgent")

// 与项目 Java 21 基线对齐，避免新 JDK 上 Java/Kotlin 默认目标版本不同而阻断回归。
kotlin.compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
java.sourceCompatibility = JavaVersion.VERSION_21
java.targetCompatibility = JavaVersion.VERSION_21

// Support Gradle 9 caching seeing the mockito agent
private class MockitoAgentArgumentProvider(
    @get:Classpath val agentJar: FileCollection
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-javaagent:${agentJar.singleFile}")
}

dependencies {
    testImplementation(libs.junit)
    @Suppress("AvoidDuplicateDependencies") // false positive
    testImplementation(libs.mockito)
    @Suppress("AvoidDuplicateDependencies")
    mockitoAgent(libs.mockito) { isTransitive = false }
}

tasks {
    test {
        workingDir = file("../android/assets")
        testLogging.lifecycle {
            events(
                    TestLogEvent.FAILED,
                    TestLogEvent.STANDARD_ERROR,
                    TestLogEvent.STANDARD_OUT
            )

            exceptionFormat = TestExceptionFormat.FULL
        }

        jvmArgumentProviders.add(MockitoAgentArgumentProvider(mockitoAgent))

        // Forward latency-test save file path to the test JVM
        System.getProperty("unciv.nextTurnSaveFile")?.let { systemProperty("unciv.nextTurnSaveFile", it) }
    }
}

sourceSets {
    test {
        java.srcDir("src")
    }
}

eclipse.project {
    name = "${BuildConfig.appName}-tests"
}
