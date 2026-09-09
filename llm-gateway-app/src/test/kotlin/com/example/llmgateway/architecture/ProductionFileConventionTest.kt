package com.example.llmgateway.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.declaration.KoObjectDeclaration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class ProductionFileConventionTest : FunSpec({
    val root = Path.of(Konsist.projectRootPath)
    val production = Konsist.scopeFromProduction()

    test("architecture scope includes every production Kotlin file in the gateway modules") {
        val sourceRoots = Files.list(root).use { paths ->
            paths.filter { Files.exists(it.resolve("build.gradle.kts")) }
                .map { it.resolve("src/main/kotlin") }
                .filter(Files::isDirectory)
                .toList()
        }
        check(sourceRoots.isNotEmpty()) { "No production source roots found" }
        val expected = sourceRoots.flatMap { sourceRoot ->
            Files.walk(sourceRoot).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .map { it.toAbsolutePath().normalize() }.toList()
            }
        }.toSet()
        val actual = production.files.map { Path.of(it.path).toAbsolutePath().normalize() }.toSet()
        actual shouldBe expected
        println("Production source inventory: ${sourceRoots.size} modules, ${actual.size} Kotlin files")
    }

    test("each named production type is top-level and owns a matching file") {
        val violations = production.files.flatMap(::fileConventionViolations)
        check(violations.isEmpty()) { violations.joinToString("\n") }
    }

    test("file rules distinguish declarations from companion members anonymous expressions and utility functions") {
        val fixtures = "llm-gateway-app/src/test/resources/architecture/file-conventions"
        fun file(relative: String) = Konsist.scopeFromFile("$fixtures/$relative").files.single()
        listOf("valid/ValidType.kt", "valid/ValidValue.kt", "valid/utility.kt").forEach {
            fileConventionViolations(file(it)) shouldBe emptyList()
        }
        fileConventionViolations(file("invalid/MultipleTypes.kt")).any { "multiple named types" in it } shouldBe true
        fileConventionViolations(file("invalid/NestedType.kt")).any { "nested/local type" in it } shouldBe true
        fileConventionViolations(file("invalid/Local.kt")).any { "nested/local type" in it } shouldBe true
        fileConventionViolations(file("invalid/Misnamed.kt")).any { "file name" in it } shouldBe true
    }
})

private fun fileConventionViolations(file: KoFileDeclaration): List<String> {
    val types = file.classesAndInterfacesAndObjects(includeNested = true, includeLocal = true)
        .filterNot { it is KoObjectDeclaration && it.hasCompanionModifier }
    val fileName = Path.of(file.path).fileName.toString().removeSuffix(".kt")
    return buildList {
        if (types.size > 1) add("${file.path}: multiple named types ${types.map { it.name }}")
        types.forEach { declaration ->
            if (!declaration.isTopLevel) add("${file.path}: nested/local type ${declaration.name}")
            if (declaration.name != fileName) add("${file.path}: file name must match ${declaration.name}")
        }
    }
}
