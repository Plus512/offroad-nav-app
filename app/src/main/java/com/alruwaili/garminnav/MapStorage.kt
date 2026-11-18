package com.alruwaili.garminnav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * يحفظ ملف الخريطة في مجلد يختاره المستخدم مرة واحدة خارج تخزين التطبيق الخاص،
 * عبر إطار عمل الوصول للتخزين (SAF — Storage Access Framework)، بحيث يبقى الملف
 * موجوداً حتى لو حُذف التطبيق أو حُدِّث بالكامل. أي ملف داخل filesDir (تخزين
 * التطبيق الخاص، وهو ما كان يُستخدم سابقاً) يُحذف تلقائياً وبلا أي استثناء عند
 * حذف التطبيق — هذا قيد في نظام أندرويد نفسه (التخزين المحصور/scoped storage،
 * مُفعَّل إلزامياً لأن التطبيق يستهدف Android 14)، وليس خياراً يمكن للتطبيق تجاوزه.
 *
 * ملاحظة مهمة: لا تتوفر هنا بيئة تشغيل أندرويد فعلية لتجربة هذا الجزء تشغيلياً —
 * رجعتُ واجهات SAF الرسمية بعناية ودققتها منطقياً خطوة بخطوة، لكنه يبقى غير
 * مُختبَر على جهاز فعلي. يلزم اختباره جيداً (تحميل خريطة جديدة، حذف التطبيق،
 * إعادة تثبيته، التأكد أن الخريطة ما زالت موجودة بدون إعادة تحميل) والإخبار
 * بأي خلل يظهر.
 */
object MapStorage {
    private const val PREFS = "map_storage"
    private const val KEY_TREE_URI = "tree_uri"
    const val MAP_FILE_NAME = "almisharIMAP.img"
    private const val TMP_FILE_NAME = "almisharIMAP.img.part"

    // -------- اختيار المجلد الدائم (يُسأل المستخدم مرة واحدة فقط) --------

    fun hasPersistentFolder(ctx: Context): Boolean = treeUri(ctx) != null

    private fun treeUri(ctx: Context): Uri? {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE_URI, null) ?: return null
        return try { Uri.parse(s) } catch (e: Exception) { null }
    }

    /** الـIntent الذي يفتح منتقي مجلدات أندرويد؛ يُستدعى عبر startActivityForResult. */
    fun pickFolderIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

    /** يُستدعى من onActivityResult بعد أن يختار المستخدم المجلد. */
    fun onFolderPicked(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) { /* بعض مزودي التخزين لا يدعمون صلاحية دائمة؛ نتجاهل ونحاول القراءة/الكتابة بأي حال */ }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    // -------- البحث عن الملف داخل المجلد المختار --------

    private fun parentDocUri(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun findChildByName(ctx: Context, name: String): Uri? {
        val tree = treeUri(ctx) ?: return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return try {
            ctx.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (idIdx >= 0 && nameIdx >= 0 && c.getString(nameIdx) == name) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(idIdx))
                    }
                }
                null
            }
        } catch (e: Exception) { null }
    }

    private fun findMapDocument(ctx: Context): Uri? = findChildByName(ctx, MAP_FILE_NAME)

    // -------- التوافق مع النسخة القديمة (داخل تخزين التطبيق) --------

    /** مسار النسخة القديمة (تُحذف عند حذف التطبيق) — للتوافق فقط مع من حمّلها قبل هذا التحديث. */
    fun legacyMapFile(ctx: Context): File = File(ctx.filesDir, "maps/$MAP_FILE_NAME")

    // -------- حالة الخريطة --------

    fun mapExists(ctx: Context): Boolean = findMapDocument(ctx) != null || legacyMapFile(ctx).exists()

    /** هل الخريطة محفوظة في المجلد الدائم فعلاً (لا النسخة القديمة المؤقتة)؟ */
    fun mapIsPersistent(ctx: Context): Boolean = findMapDocument(ctx) != null

    fun mapSizeMb(ctx: Context): Long {
        findMapDocument(ctx)?.let { docUri ->
            try {
                ctx.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        if (i >= 0) return c.getLong(i) / 1024 / 1024
                    }
                }
            } catch (e: Exception) { }
        }
        val f = legacyMapFile(ctx)
        return if (f.exists()) f.length() / 1024 / 1024 else 0L
    }

    // -------- القراءة --------

    /** يفتح مجرى قراءة للخريطة: من المجلد الدائم إن وُجدت فيه، وإلا من النسخة القديمة. */
    fun openMapInputStream(ctx: Context): InputStream? {
        findMapDocument(ctx)?.let { uri ->
            try { return ctx.contentResolver.openInputStream(uri) } catch (e: Exception) { }
        }
        val f = legacyMapFile(ctx)
        return if (f.exists()) f.inputStream() else null
    }

    // -------- الكتابة (تنزيل/استيراد) بأسلوب آمن: ملف مؤقت ثم إعادة تسمية --------

    /** ينشئ ملفاً مؤقتاً (.part) داخل المجلد الدائم ويعيد مجرى كتابة له، أو null إن لم يُختر مجلد بعد. */
    fun createTempOutputStream(ctx: Context): Pair<Uri, OutputStream>? {
        val tree = treeUri(ctx) ?: return null
        return try {
            // أزل أي ملف مؤقت متبقٍ من محاولة سابقة فشلت
            findChildByName(ctx, TMP_FILE_NAME)?.let {
                try { DocumentsContract.deleteDocument(ctx.contentResolver, it) } catch (e: Exception) { }
            }
            val tmpUri = DocumentsContract.createDocument(
                ctx.contentResolver, parentDocUri(tree), "application/octet-stream", TMP_FILE_NAME
            ) ?: return null
            val out = ctx.contentResolver.openOutputStream(tmpUri) ?: return null
            tmpUri to out
        } catch (e: Exception) { null }
    }

    /** يُستدعى بعد نجاح الكتابة الكاملة: يحذف النسخة النهائية القديمة إن وُجدت، ويُعيد تسمية الملف المؤقت إليها. */
    fun finalizeDownload(ctx: Context, tmpUri: Uri): Boolean {
        return try {
            findChildByName(ctx, MAP_FILE_NAME)?.let {
                try { DocumentsContract.deleteDocument(ctx.contentResolver, it) } catch (e: Exception) { }
            }
            DocumentsContract.renameDocument(ctx.contentResolver, tmpUri, MAP_FILE_NAME) != null
        } catch (e: Exception) { false }
    }

    /** يُستدعى عند فشل التنزيل/الاستيراد: يحذف الملف المؤقت الناقص بدل تركه متراكماً. */
    fun deleteTemp(ctx: Context, tmpUri: Uri) {
        try { DocumentsContract.deleteDocument(ctx.contentResolver, tmpUri) } catch (e: Exception) { }
    }
}
