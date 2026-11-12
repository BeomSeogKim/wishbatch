package app.web.security

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Collections

internal class BoundedRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
    private val fields: Map<String, Array<String>> by lazy {
        val values = mutableMapOf<String, MutableList<String>>()
        fun decode(encoded: String) {
            val charset = characterEncoding?.let { java.nio.charset.Charset.forName(it) } ?: UTF_8
            encoded.split('&').filter { it.isNotEmpty() }.forEach { field ->
                val parts = field.split('=', limit = 2)
                val name = URLDecoder.decode(parts[0], charset)
                val value = URLDecoder.decode(parts.getOrElse(1) { "" }, charset)
                values.getOrPut(name) { mutableListOf() }.add(value)
            }
        }
        queryString?.let(::decode)
        if (contentType?.substringBefore(';')?.trim()?.equals("application/x-www-form-urlencoded", true) == true) {
            val charset = characterEncoding?.let { java.nio.charset.Charset.forName(it) } ?: UTF_8
            decode(body.toString(charset))
        }
        super.getParameterMap().forEach { (name, entries) -> values.putIfAbsent(name, entries.toMutableList()) }
        values.mapValues { it.value.toTypedArray() }
    }

    override fun getParameterMap(): Map<String, Array<String>> = fields
    override fun getParameter(name: String): String? = fields[name]?.firstOrNull()
    override fun getParameterValues(name: String): Array<String>? = fields[name]
    override fun getParameterNames() = Collections.enumeration(fields.keys)
    override fun getInputStream(): ServletInputStream {
        val input = ByteArrayInputStream(body)
        return object : ServletInputStream() {
            override fun read() = input.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int) = input.read(bytes, offset, length)
            override fun isFinished() = input.available() == 0
            override fun isReady() = true
            override fun setReadListener(listener: ReadListener) {
                if (!isFinished) listener.onDataAvailable()
                if (isFinished) listener.onAllDataRead()
            }
        }
    }

    override fun getReader() = BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8"))
}
