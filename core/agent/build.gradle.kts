plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Contract types need Flow/suspend only. The architecture map ALLOWS
    // :core:agent -> :core:engine/:core:ai/:core:model, but the A1 contracts
    // reference none of their types (TurnContext uses opaque ids), so no
    // project dependencies are declared yet — A2 adds them when it needs them.
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
