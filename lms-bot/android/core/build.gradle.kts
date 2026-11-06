// Общий с Telegram-ботом слой LMS: модели, разбор страниц lms.tuit.uz, OneID, расписание.
// Исходники не копируются — берутся прямо из ../../src/main/java, чтобы бот и приложение
// всегда разбирали LMS одинаково.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    main {
        java {
            setSrcDirs(listOf("../../src/main/java"))
            include(
                "uz/tuit/lmsbot/model/**",
                "uz/tuit/lmsbot/service/**",
                "uz/tuit/lmsbot/util/**",
                "uz/tuit/lmsbot/config/**",
            )
            // Резервная копия состояния и реестр студентов нужны только серверу бота.
            exclude(
                "uz/tuit/lmsbot/service/StateBackup.java",
                "uz/tuit/lmsbot/service/StudentRegistry.java",
            )
        }
        resources.setSrcDirs(emptyList<String>())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jsoup:jsoup:1.17.2")
    api("com.google.zxing:core:3.5.3")
    api("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.16.1")
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
}
