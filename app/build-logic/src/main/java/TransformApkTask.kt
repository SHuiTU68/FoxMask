import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.dsl.ApkSigningConfig
import com.android.builder.internal.packaging.IncrementalPackager
import com.android.ide.common.signing.KeystoreHelper
import com.android.tools.build.apkzlib.sign.SigningExtension
import com.android.tools.build.apkzlib.sign.SigningOptions
import com.android.tools.build.apkzlib.zfile.ZFiles
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.jar.JarFile

abstract class TransformApkTask : DefaultTask() {
    @get:Input
    abstract val signingConfig: Property<ApkSigningConfig>

    @get:InputFiles
    abstract val apkFolder: DirectoryProperty

    @get:OutputDirectory
    abstract val outFolder: DirectoryProperty

    @get:Internal
    abstract val transformations: ListProperty<(ZFile) -> Unit>

    @get:Internal
    abstract val transformationRequest: Property<ArtifactTransformationRequest<TransformApkTask>>

    @TaskAction
    fun taskAction() = transformationRequest.get().submit(this) { artifact ->
        val inFile = File(artifact.outputFile)
        val outFile = outFolder.file(inFile.name).get().asFile

        val config = signingConfig.get()
        val info = KeystoreHelper.getCertificateInfo(
            config.storeType,
            config.storeFile,
            config.storePassword,
            config.keyPassword,
            config.keyAlias
        )

        val signingOptions = SigningOptions.builder()
            .setMinSdkVersion(0)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setKey(info.key)
            .setCertificates(info.certificate)
            .setValidation(SigningOptions.Validation.ASSUME_INVALID)
            .build()
        // autoSortFiles=false：避免 apkzlib 在 close 时对 STORED+page-aligned 的
        // jniLibs（如 libkptools.so）做重排导致字节级损坏（表现为 ELF dynamic
        // section 整体左移 1 字节，linker 报 empty/missing DT_HASH/DT_GNU_HASH）。
        // APK 条目排序仅为访问优化，Android 并不要求排序，关闭不会影响安装/加载。
        val options = ZFileOptions().apply {
            noTimestamps = true
            autoSortFiles = false
        }
        outFile.parentFile?.mkdirs()
        inFile.copyTo(outFile, overwrite = true)
        ZFiles.apk(outFile, options).use {
            SigningExtension(signingOptions).register(it)
            it.get(IncrementalPackager.APP_METADATA_ENTRY_PATH)?.delete()
            it.get(IncrementalPackager.VERSION_CONTROL_INFO_ENTRY_PATH)?.delete()
            it.get(JarFile.MANIFEST_NAME)?.delete()
            it.get("assets/PublicSuffixDatabase.list")?.delete()
            transformations.get().forEach { transform -> transform(it) }
        }

        outFile
    }
}
