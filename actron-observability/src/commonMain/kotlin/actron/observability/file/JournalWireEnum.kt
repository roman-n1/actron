package actron.observability.file

internal inline fun <reified T : Enum<T>> enumNamed(name: String): T = enumValues<T>().firstOrNull { it.name == name } ?: throw ByteReader.Malformed("unknown ${T::class.simpleName} '$name'")
