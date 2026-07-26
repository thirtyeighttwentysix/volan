package io.github.thirtyeighttwentysix.volan.runtime

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class SlotTest {
    @Test
    fun `a loaded slot hands back what the query fetched`() {
        val slot = RelationSlot.loaded(listOf("a", "b"))
        slot.isLoaded shouldBe true
        slot.get("User", "posts") shouldBe listOf("a", "b")
        slot.orNull() shouldBe listOf("a", "b")
    }

    @Test
    fun `a loaded slot can hold null, which is not the same as being empty`() {
        val slot = RelationSlot.loaded<String?>(null)
        slot.isLoaded shouldBe true
        slot.get("User", "profile").shouldBeNull()
        RelationSlot.notLoaded<String?>().isLoaded shouldBe false
    }

    @Test
    fun `reading a relation the query did not load says which query to change`() {
        val thrown = runCatching { RelationSlot.notLoaded<List<String>>().get("User", "posts") }
            .exceptionOrNull()
        (thrown is VolanRelationNotLoadedException) shouldBe true
        val message = thrown?.message.orEmpty()
        message shouldContain "`User.posts` was not loaded"
        message shouldContain "include { posts { } }"
        message shouldContain "isPostsLoaded"
    }

    @Test
    fun `an unloaded slot reads as null when asked politely`() {
        RelationSlot.notLoaded<List<String>>().orNull().shouldBeNull()
    }

    @Test
    fun `slots compare by what they hold and whether they were loaded`() {
        RelationSlot.loaded(1) shouldBe RelationSlot.loaded(1)
        (RelationSlot.loaded(1) == RelationSlot.notLoaded<Int>()) shouldBe false
        RelationSlot.notLoaded<Int>().toString() shouldBe "<not loaded>"
    }

    @Test
    fun `a projection hands back the fields the query selected`() {
        val selected = SelectedFields.of(setOf("email", "name"))
        selected.contains("email") shouldBe true
        selected.require("User", "email", "alice@acme.com") shouldBe "alice@acme.com"
        selected.require<String?>("User", "name", null).shouldBeNull()
    }

    @Test
    fun `reading a field the select left out says which select to change`() {
        val thrown = runCatching { SelectedFields.of(setOf("email")).require("User", "createdAt", null) }
            .exceptionOrNull()
        (thrown is VolanFieldNotSelectedException) shouldBe true
        val message = thrown?.message.orEmpty()
        message shouldContain "`User.createdAt` was not selected"
        message shouldContain "select { createdAt }"
        message shouldContain "isCreatedAtSelected"
    }
}
