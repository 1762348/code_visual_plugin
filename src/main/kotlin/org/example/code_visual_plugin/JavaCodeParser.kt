package org.example.code_visual_plugin

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

// -------- 数据模型，前后端JSON契约 --------
data class ParseResult(
    val success: Boolean,
    val msg: String,
    val filePath: String = "",
    val packages: List<PackageNode> = emptyList(),
    // 前端契约 v1，见 GraphContract.kt；packages 保留用于向后兼容
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList()
)

data class PackageNode(
    @SerializedName("packageName") val packageName: String,
    @SerializedName("expand") val expand: Boolean = false,
    @SerializedName("classes") val classes: List<ClassNode>
)

data class ClassNode(
    val className: String,
    val summary: String = "【AI待生成】类语义总结",
    val fields: List<String>,
    val methods: List<MethodNode>
)

data class MethodNode(
    val methodName: String,
    val summary: String = "【AI待生成】方法语义总结",
    val callList: List<String>,
    val varList: List<String>,
    val importUsed: List<String>,
    val methodSource: String
)

object JavaCodeParser {
    private val gson = Gson()

    /** 对外入口：传入源码、文件路径，输出JSON字符串 */
    fun parseSource(sourceCode: String, filePath: String): String {
        return try {
            val parser = JavaParser()
            val parseResult = parser.parse(sourceCode)
            if (!parseResult.isSuccessful) {
                return gson.toJson(ParseResult(success = false, msg = "Java语法解析失败，源码存在语法错误"))
            }
            val cu: CompilationUnit = parseResult.getResult().orElseThrow()

            // 获取包名，无包名默认为 default
            val pkgName = cu.packageDeclaration.map { it.nameAsString }.orElse("default")

            // 收集import
            val importList = cu.imports.map { it.nameAsString }

            val classNodes = mutableListOf<ClassNode>()

            // 遍历类
            cu.accept(object : VoidVisitorAdapter<Unit>() {
                override fun visit(n: ClassOrInterfaceDeclaration, arg: Unit?) {
                    super.visit(n, arg)
                    if(n.isInterface) return

                    // 字段
                    val fieldNames = n.fields.flatMap { it.variables }.map { it.nameAsString }

                    // 方法
                    val methodNodes = mutableListOf<MethodNode>()
                    for (md: MethodDeclaration in n.methods) {
                        val calls = mutableSetOf<String>()
                        // 收集方法内部调用
                        md.accept(object : VoidVisitorAdapter<Unit>(){
                            override fun visit(call: MethodCallExpr, arg: Unit?) {
                                super.visit(call, arg)
                                calls.add(call.nameAsString)
                            }
                        }, Unit)

                        // Visitor遍历收集局部变量
                        val localVars = mutableSetOf<String>()
                        md.accept(object : VoidVisitorAdapter<Unit>(){
                            override fun visit(vd: VariableDeclarationExpr, arg: Unit?) {
                                super.visit(vd, arg)
                                vd.variables.forEach { v ->
                                    localVars.add(v.nameAsString)
                                }
                            }
                        }, Unit)

                        val methodSrc = md.toString()
                        methodNodes.add(
                            MethodNode(
                                methodName = md.nameAsString,
                                callList = calls.toList(),
                                varList = localVars.toList(),
                                importUsed = importList,
                                methodSource = methodSrc
                            )
                        )
                    }

                    classNodes.add(
                        ClassNode(
                            className = n.nameAsString,
                            fields = fieldNames,
                            methods = methodNodes
                        )
                    )
                }
            }, Unit)

            val pkgNode = PackageNode(
                packageName = pkgName,
                expand = false,
                classes = classNodes
            )

            val (graphNodes, graphEdges) = GraphBuilder.fromClasses(pkgName, classNodes)

            val result = ParseResult(
                success = true,
                msg = "ok",
                filePath = filePath,
                packages = listOf(pkgNode),
                nodes = graphNodes,
                edges = graphEdges
            )
            gson.toJson(result)

        } catch (e: Exception) {
            val fail = ParseResult(success = false, msg = "解析异常:${e.message}")
            gson.toJson(fail)
        }
    }

    // 自测兜底，开发调试用
    fun runSelfTest() {
        val testCode = """
            public class Main{
                public static void main(String[] args){
                    for(int i=1;i<=10;i++){
                        System.out.println(i);
                    }
                }
            }
        """.trimIndent()
        val json = parseSource(testCode,"Main.java")
        println("[JavaParser自测输出] $json")
    }
}
