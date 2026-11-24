package com.tiggy.qwenimage.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class KnowledgeBaseSummary(
    val id: String,
    val name: String,
    val description: String,
    val documentCount: Int,
    val chunkCount: Int,
    val embeddedChunkCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

data class KnowledgeDocumentSummary(
    val id: String,
    val kbId: String,
    val fileName: String,
    val charCount: Int,
    val chunkCount: Int,
    val embeddedChunkCount: Int,
    val createdAt: Long
)

/**
 * Pure Android SQLite storage for local knowledge bases, documents, text chunks, and Float32 embeddings.
 */
class KnowledgeBaseStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE knowledge_bases (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE kb_documents (
                id TEXT PRIMARY KEY,
                kb_id TEXT NOT NULL,
                file_name TEXT NOT NULL,
                char_count INTEGER NOT NULL,
                chunk_count INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(kb_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE kb_chunks (
                id TEXT PRIMARY KEY,
                kb_id TEXT NOT NULL,
                doc_id TEXT NOT NULL,
                doc_name TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                content TEXT NOT NULL,
                embedding BLOB,
                embedding_model_id TEXT,
                FOREIGN KEY(doc_id) REFERENCES kb_documents(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_kb_docs_kb ON kb_documents(kb_id)")
        db.execSQL("CREATE INDEX idx_kb_chunks_kb ON kb_chunks(kb_id)")
        db.execSQL("CREATE INDEX idx_kb_chunks_doc ON kb_chunks(doc_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun listBases(): List<KnowledgeBaseSummary> {
        val db = readableDatabase
        val out = ArrayList<KnowledgeBaseSummary>()
        val sql = """
            SELECT b.id, b.name, b.description, b.created_at, b.updated_at,
                   (SELECT COUNT(*) FROM kb_documents d WHERE d.kb_id = b.id) AS doc_cnt,
                   (SELECT COUNT(*) FROM kb_chunks c WHERE c.kb_id = b.id) AS chunk_cnt,
                   (SELECT COUNT(*) FROM kb_chunks c WHERE c.kb_id = b.id AND c.embedding IS NOT NULL) AS emb_cnt
            FROM knowledge_bases b
            ORDER BY b.updated_at DESC
        """.trimIndent()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    KnowledgeBaseSummary(
                        id = c.getString(0),
                        name = c.getString(1),
                        description = c.getString(2),
                        createdAt = c.getLong(3),
                        updatedAt = c.getLong(4),
                        documentCount = c.getInt(5),
                        chunkCount = c.getInt(6),
                        embeddedChunkCount = c.getInt(7)
                    )
                )
            }
        }
        return out
    }

    fun createBase(name: String, description: String = ""): KnowledgeBaseSummary {
        val now = System.currentTimeMillis()
        val id = "kb-" + UUID.randomUUID().toString().take(12)
        val cleanName = name.trim().take(60).ifEmpty { "Knowledge Base" }
        val cleanDesc = description.trim().take(200)
        val cv = ContentValues().apply {
            put("id", id)
            put("name", cleanName)
            put("description", cleanDesc)
            put("created_at", now)
            put("updated_at", now)
        }
        writableDatabase.insertOrThrow("knowledge_bases", null, cv)
        return KnowledgeBaseSummary(id, cleanName, cleanDesc, 0, 0, 0, now, now)
    }

    fun deleteBase(kbId: String) {
        writableDatabase.delete("knowledge_bases", "id = ?", arrayOf(kbId))
    }

    fun listDocuments(kbId: String): List<KnowledgeDocumentSummary> {
        val out = ArrayList<KnowledgeDocumentSummary>()
        val sql = """
            SELECT d.id, d.kb_id, d.file_name, d.char_count, d.chunk_count, d.created_at,
                   (SELECT COUNT(*) FROM kb_chunks c WHERE c.doc_id = d.id AND c.embedding IS NOT NULL) AS emb_cnt
            FROM kb_documents d
            WHERE d.kb_id = ?
            ORDER BY d.created_at DESC
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(kbId)).use { c ->
            while (c.moveToNext()) {
                out.add(
                    KnowledgeDocumentSummary(
                        id = c.getString(0),
                        kbId = c.getString(1),
                        fileName = c.getString(2),
                        charCount = c.getInt(3),
                        chunkCount = c.getInt(4),
                        createdAt = c.getLong(5),
                        embeddedChunkCount = c.getInt(6)
                    )
                )
            }
        }
        return out
    }

    fun addDocumentWithChunks(
        kbId: String,
        fileName: String,
        charCount: Int,
        chunks: List<String>,
        embeddings: List<FloatArray?>,
        embeddingModelId: String?
    ): String {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val docId = "doc-" + UUID.randomUUID().toString().take(12)
        db.beginTransaction()
        try {
            val docCv = ContentValues().apply {
                put("id", docId)
                put("kb_id", kbId)
                put("file_name", fileName)
                put("char_count", charCount)
                put("chunk_count", chunks.size)
                put("created_at", now)
            }
            db.insertOrThrow("kb_documents", null, docCv)

            chunks.forEachIndexed { idx, text ->
                val emb = embeddings.getOrNull(idx)
                val chunkCv = ContentValues().apply {
                    put("id", "$docId-$idx")
                    put("kb_id", kbId)
                    put("doc_id", docId)
                    put("doc_name", fileName)
                    put("chunk_index", idx)
                    put("content", text)
                    if (emb != null) {
                        put("embedding", KnowledgeBaseSearch.encodeFloatArray(emb))
                        put("embedding_model_id", embeddingModelId)
                    }
                }
                db.insertOrThrow("kb_chunks", null, chunkCv)
            }

            val upd = ContentValues().apply { put("updated_at", now) }
            db.update("knowledge_bases", upd, "id = ?", arrayOf(kbId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return docId
    }

    fun deleteDocument(kbId: String, docId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("kb_documents", "id = ?", arrayOf(docId))
            val upd = ContentValues().apply { put("updated_at", System.currentTimeMillis()) }
            db.update("knowledge_bases", upd, "id = ?", arrayOf(kbId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadChunksForBase(kbId: String): List<KnowledgeChunkRecord> {
        val out = ArrayList<KnowledgeChunkRecord>()
        readableDatabase.rawQuery(
            "SELECT id, kb_id, doc_id, doc_name, chunk_index, content, embedding, embedding_model_id FROM kb_chunks WHERE kb_id = ? ORDER BY doc_id, chunk_index",
            arrayOf(kbId)
        ).use { c ->
            while (c.moveToNext()) {
                val blob = if (c.isNull(6)) null else c.getBlob(6)
                out.add(
                    KnowledgeChunkRecord(
                        id = c.getString(0),
                        kbId = c.getString(1),
                        docId = c.getString(2),
                        docName = c.getString(3),
                        chunkIndex = c.getInt(4),
                        text = c.getString(5),
                        embedding = KnowledgeBaseSearch.decodeFloatArray(blob),
                        embeddingModelId = if (c.isNull(7)) null else c.getString(7)
                    )
                )
            }
        }
        return out
    }

    fun updateChunkEmbedding(chunkId: String, embedding: FloatArray, modelId: String) {
        val cv = ContentValues().apply {
            put("embedding", KnowledgeBaseSearch.encodeFloatArray(embedding))
            put("embedding_model_id", modelId)
        }
        writableDatabase.update("kb_chunks", cv, "id = ?", arrayOf(chunkId))
    }

    companion object {
        private const val DB_NAME = "omni_knowledge.db"
        private const val DB_VERSION = 1

        @Volatile private var instance: KnowledgeBaseStore? = null

        fun get(context: Context): KnowledgeBaseStore =
            instance ?: synchronized(this) {
                instance ?: KnowledgeBaseStore(context.applicationContext).also { instance = it }
            }
    }
}
