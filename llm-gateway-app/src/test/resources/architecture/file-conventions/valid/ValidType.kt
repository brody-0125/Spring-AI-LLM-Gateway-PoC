package architecture.fixtures.valid

class ValidType {
    companion object Factory {
        const val VALUE = 1
    }

    fun callback() = object : Runnable {
        override fun run() = Unit
    }
}
