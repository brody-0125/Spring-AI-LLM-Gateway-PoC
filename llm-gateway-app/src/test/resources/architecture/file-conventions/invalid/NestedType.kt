package architecture.fixtures.invalid

class NestedType {
    class Child
    object NamedChild

    companion object {
        class HiddenInsideCompanion
    }
}
