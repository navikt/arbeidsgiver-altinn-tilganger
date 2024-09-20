package no.nav.fager.fakes

abstract class FakeClientBase {
    private val functionCalls: MutableList<FunctionCall> = mutableListOf()

    protected fun addFunctionCall(funcName: String, vararg args: Any) {
        val call = FunctionCall(funcName, args.toList())
        functionCalls.add(call)
    }

    fun getCallCountWithArgs(funcName: String, vararg args: Any): Int{
        val count = functionCalls.count { it.funcName == funcName && it.args == args.toList() }
        return count
    }

    fun getCallCount(funcName: String): Int{
        return functionCalls.count { it.funcName == funcName }
    }
}

data class FunctionCall(val funcName: String, val args: List<Any>)