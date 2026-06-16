package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipFile

class InspectCommon {
    private val sb = StringBuilder()

    private fun inspectClass(clazz: Class<*>) {
        try {
            sb.append("========================================\n")
            sb.append("Class: ${clazz.name}\n")
            sb.append("Modifiers: ${Modifier.toString(clazz.modifiers)}\n")
            sb.append("Superclass: ${clazz.superclass?.name}\n")
            sb.append("Interfaces: ${clazz.interfaces.joinToString { it.name }}\n")
            
            sb.append("\nConstructors:\n")
            clazz.declaredConstructors.forEach { constructor ->
                val params = constructor.parameterTypes.joinToString { it.name }
                sb.append("  ${Modifier.toString(constructor.modifiers)} ${clazz.simpleName}($params)\n")
            }

            sb.append("\nFields:\n")
            clazz.declaredFields.forEach { field ->
                sb.append("  ${Modifier.toString(field.modifiers)} ${field.type.name} ${field.name}\n")
            }

            sb.append("\nMethods:\n")
            clazz.declaredMethods.forEach { method ->
                val params = method.parameterTypes.joinToString { it.name }
                sb.append("  ${Modifier.toString(method.modifiers)} ${method.returnType.name} ${method.name}($params)\n")
            }
            sb.append("========================================\n\n")
        } catch (e: Throwable) {
            sb.append("Error inspecting class ${clazz.name}: ${e.message}\n")
        }
    }

    @Test
    fun runInspection() {
        try {
            val classLocation = ExtensionClient::class.java.protectionDomain.codeSource.location
            sb.append("Jar location: $classLocation\n")
            
            val classNames = mutableListOf<String>()
            
            if (classLocation.protocol == "file") {
                val file = File(classLocation.toURI())
                if (file.isFile && file.name.endsWith(".jar")) {
                    ZipFile(file).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.name.startsWith("dev/brahmkshatriya/echo/common/") && entry.name.endsWith(".class")) {
                                val className = entry.name.replace("/", ".").removeSuffix(".class")
                                if (!className.contains("$") || className.substringAfter("$").toIntOrNull() == null) {
                                    classNames.add(className)
                                }
                            }
                        }
                    }
                }
            }

            sb.append("Found ${classNames.size} classes in dev.brahmkshatriya.echo.common package.\n")
            classNames.sorted().forEach { className ->
                try {
                    val clazz = Class.forName(className)
                    inspectClass(clazz)
                } catch (e: Exception) {
                    sb.append("Could not load class: $className\n")
                }
            }
            
            File("inspect_result.txt").writeText(sb.toString())
            println("Inspection output written to inspect_result.txt")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
