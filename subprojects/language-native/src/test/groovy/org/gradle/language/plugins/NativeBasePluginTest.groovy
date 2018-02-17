/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.language.plugins

import org.gradle.api.Task
import org.gradle.api.artifacts.PublishArtifact
import org.gradle.api.component.ComponentWithVariants
import org.gradle.api.component.SoftwareComponent
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal
import org.gradle.api.internal.component.SoftwareComponentInternal
import org.gradle.api.internal.component.UsageContext
import org.gradle.api.internal.provider.Providers
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.TaskDependency
import org.gradle.api.tasks.TaskDependencyMatchers
import org.gradle.language.ComponentWithBinaries
import org.gradle.language.ComponentWithOutputs
import org.gradle.language.ProductionComponent
import org.gradle.language.PublishableComponent
import org.gradle.language.internal.DefaultBinaryCollection
import org.gradle.language.nativeplatform.internal.ComponentWithNames
import org.gradle.language.nativeplatform.internal.ConfigurableComponentWithExecutable
import org.gradle.language.nativeplatform.internal.ConfigurableComponentWithLinkUsage
import org.gradle.language.nativeplatform.internal.ConfigurableComponentWithRuntimeUsage
import org.gradle.language.nativeplatform.internal.ConfigurableComponentWithSharedLibrary
import org.gradle.language.nativeplatform.internal.ConfigurableComponentWithStaticLibrary
import org.gradle.language.nativeplatform.internal.Names
import org.gradle.language.nativeplatform.internal.PublicationAwareComponent
import org.gradle.nativeplatform.platform.internal.NativePlatformInternal
import org.gradle.nativeplatform.tasks.CreateStaticLibrary
import org.gradle.nativeplatform.tasks.ExtractSymbols
import org.gradle.nativeplatform.tasks.InstallExecutable
import org.gradle.nativeplatform.tasks.LinkExecutable
import org.gradle.nativeplatform.tasks.LinkSharedLibrary
import org.gradle.nativeplatform.tasks.StripSymbols
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import spock.lang.Specification

class NativeBasePluginTest extends Specification {
    @Rule
    TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider()
    def projectDir = tmpDir.createDir("project")
    def project = ProjectBuilder.builder().withProjectDir(projectDir).withName("testComponent").build()

    def "registers each binary of a component as it becomes known"() {
        def b1 = Stub(SoftwareComponent)
        b1.name >> "b1"
        def b2 = Stub(SoftwareComponent)
        b2.name >> "b2"
        def component = Stub(ComponentWithBinaries)
        def binaries = new DefaultBinaryCollection(SoftwareComponent, null)
        component.binaries >> binaries

        given:
        project.pluginManager.apply(NativeBasePlugin)

        when:
        project.components.add(component)

        then:
        project.components.size() == 1

        when:
        binaries.add(b1)
        binaries.add(b2)
        binaries.realizeNow()

        then:
        project.components.size() == 3
        project.components.b1 == b1
        project.components.b2 == b2
    }

    def "assemble task does nothing when no main component"() {
        def component = Stub(SoftwareComponent)
        component.name >> 'not-main'

        given:
        project.pluginManager.apply(NativeBasePlugin)

        when:
        project.components.add(component)

        then:
        project.tasks['assemble'] TaskDependencyMatchers.dependsOn()
    }

    def "assemble task builds outputs of development binary of main component"() {
        def binary1 = binary('debug', 'debugInstall')
        def binary2 = binary('release', 'releaseInstall')
        def binaries = new DefaultBinaryCollection(SoftwareComponent, null)
        binaries.add(binary1)
        binaries.add(binary2)
        def component = Stub(TestComponent)
        component.binaries >> binaries
        component.developmentBinary >> Providers.of(binary1)

        given:
        project.pluginManager.apply(NativeBasePlugin)

        when:
        project.components.add(component)
        binaries.realizeNow()

        then:
        project.tasks['assemble'] TaskDependencyMatchers.dependsOn('debugInstall')
    }

    def "adds assemble task for each binary of main component"() {
        def binary1 = binary('debug', 'installDebug')
        def binary2 = binary('release', 'installRelease')
        def binaries = new DefaultBinaryCollection(SoftwareComponent, null)
        binaries.add(binary1)
        binaries.add(binary2)
        def component = Stub(TestComponent)
        component.binaries >> binaries
        component.developmentBinary >> Providers.of(binary1)

        given:
        project.pluginManager.apply(NativeBasePlugin)

        when:
        project.components.add(component)
        binaries.realizeNow()

        then:
        project.tasks['assembleDebug'] TaskDependencyMatchers.dependsOn('installDebug')
        project.tasks['assembleRelease'] TaskDependencyMatchers.dependsOn('installRelease')
    }

    def "adds tasks to assemble a static library"() {
        def toolProvider = Stub(PlatformToolProvider)
        toolProvider.getStaticLibraryName(_) >> { String p -> p + ".lib" }

        def linkFileProp = project.objects.property(RegularFile)
        def createTaskProp = project.objects.property(CreateStaticLibrary)

        def staticLib = Stub(ConfigurableComponentWithStaticLibrary)
        staticLib.name >> "windowsDebug"
        staticLib.names >> Names.of("windowsDebug")
        staticLib.targetPlatform >> Stub(NativePlatformInternal)
        staticLib.toolChain >> Stub(NativeToolChainInternal)
        staticLib.platformToolProvider >> toolProvider
        staticLib.baseName >> Providers.of("test_lib")
        staticLib.linkFile >> linkFileProp
        staticLib.createTask >> createTaskProp

        given:
        project.pluginManager.apply(NativeBasePlugin)
        project.components.add(staticLib)

        expect:
        def createTask = project.tasks['createWindowsDebug']
        createTask instanceof CreateStaticLibrary
        createTask.binaryFile.get().asFile == projectDir.file("build/lib/windows/debug/test_lib.lib")

        and:
        linkFileProp.get().asFile == createTask.binaryFile.get().asFile
        createTaskProp.get() == createTask
    }

    def "adds tasks to assemble a shared library"() {
        def toolProvider = Stub(PlatformToolProvider)
        toolProvider.getSharedLibraryName(_) >> { String p -> p + ".dll" }

        def runtimeFileProp = project.objects.property(RegularFile)
        def linkTaskProp = project.objects.property(LinkSharedLibrary)

        def sharedLibrary = Stub(ConfigurableComponentWithSharedLibrary)
        sharedLibrary.name >> "windowsDebug"
        sharedLibrary.names >> Names.of("windowsDebug")
        sharedLibrary.targetPlatform >> Stub(NativePlatformInternal)
        sharedLibrary.toolChain >> Stub(NativeToolChainInternal)
        sharedLibrary.platformToolProvider >> toolProvider
        sharedLibrary.baseName >> Providers.of("test_lib")
        sharedLibrary.runtimeFile >> runtimeFileProp
        sharedLibrary.linkTask >> linkTaskProp

        given:
        project.pluginManager.apply(NativeBasePlugin)
        project.components.add(sharedLibrary)

        expect:
        def linkTask = project.tasks['linkWindowsDebug']
        linkTask instanceof LinkSharedLibrary
        linkTask.binaryFile.get().asFile == projectDir.file("build/lib/windows/debug/test_lib.dll")

        and:
        runtimeFileProp.get().asFile == linkTask.binaryFile.get().asFile
        linkTaskProp.get() == linkTask
    }

    def "adds tasks to assemble and strip a shared library"() {
        def toolProvider = Stub(PlatformToolProvider)
        toolProvider.getSharedLibraryName(_) >> { String p -> p + ".dll" }
        toolProvider.getLibrarySymbolFileName(_) >> { String p -> p + ".dll.pdb" }
        toolProvider.requiresDebugBinaryStripping() >> true

        def runtimeFileProp = project.objects.property(RegularFile)
        def linkTaskProp = project.objects.property(LinkSharedLibrary)

        def sharedLibrary = Stub(ConfigurableComponentWithSharedLibrary)
        sharedLibrary.name >> "windowsDebug"
        sharedLibrary.names >> Names.of("windowsDebug")
        sharedLibrary.debuggable >> true
        sharedLibrary.optimized >> true
        sharedLibrary.targetPlatform >> Stub(NativePlatformInternal)
        sharedLibrary.toolChain >> Stub(NativeToolChainInternal)
        sharedLibrary.platformToolProvider >> toolProvider
        sharedLibrary.baseName >> Providers.of("test_lib")
        sharedLibrary.runtimeFile >> runtimeFileProp
        sharedLibrary.linkTask >> linkTaskProp

        given:
        project.pluginManager.apply(NativeBasePlugin)
        project.components.add(sharedLibrary)

        expect:
        def linkTask = project.tasks['linkWindowsDebug']
        linkTask instanceof LinkSharedLibrary
        linkTask.binaryFile.get().asFile == projectDir.file("build/lib/windows/debug/test_lib.dll")

        def stripTask = project.tasks['stripSymbolsWindowsDebug']
        stripTask instanceof StripSymbols
        stripTask.binaryFile.get().asFile == linkTask.binaryFile.get().asFile
        stripTask.outputFile.get().asFile == projectDir.file("build/lib/windows/debug/stripped/test_lib.dll")

        def extractTask = project.tasks['extractSymbolsWindowsDebug']
        extractTask instanceof ExtractSymbols
        extractTask.binaryFile.get().asFile == linkTask.binaryFile.get().asFile
        extractTask.symbolFile.get().asFile == projectDir.file("build/lib/windows/debug/stripped/test_lib.dll.pdb")

        and:
        runtimeFileProp.get().asFile == stripTask.outputFile.get().asFile
        linkTaskProp.get() == linkTask
    }

    def "adds tasks to assemble an executable"() {
        def toolProvider = Stub(PlatformToolProvider)
        toolProvider.getExecutableName(_) >> { String p -> p + ".exe" }

        def exeFileProp = project.objects.property(RegularFile)
        def debugExeFileProp = project.objects.property(RegularFile)
        def linkTaskProp = project.objects.property(LinkExecutable)
        def installDirProp = project.objects.property(Directory)
        def installTaskProp = project.objects.property(InstallExecutable)

        def executable = Stub(ConfigurableComponentWithExecutable)
        executable.name >> "windowsDebug"
        executable.names >> Names.of("windowsDebug")
        executable.targetPlatform >> Stub(NativePlatformInternal)
        executable.toolChain >> Stub(NativeToolChainInternal)
        executable.platformToolProvider >> toolProvider
        executable.baseName >> Providers.of("test_app")
        executable.executableFile >> exeFileProp
        executable.debuggerExecutableFile >> debugExeFileProp
        executable.linkTask >> linkTaskProp
        executable.installDirectory >> installDirProp
        executable.installTask >> installTaskProp

        given:
        project.pluginManager.apply(NativeBasePlugin)
        project.components.add(executable)

        expect:
        def linkTask = project.tasks['linkWindowsDebug']
        linkTask instanceof LinkExecutable
        linkTask.binaryFile.get().asFile == projectDir.file("build/exe/windows/debug/test_app.exe")

        def installTask = project.tasks['installWindowsDebug']
        installTask instanceof InstallExecutable
        installTask.sourceFile.get().asFile == linkTask.binaryFile.get().asFile
        installTask.installDirectory.get().asFile == projectDir.file("build/install/windows/debug")

        and:
        exeFileProp.get().asFile == linkTask.binaryFile.get().asFile
        debugExeFileProp.get().asFile == linkTask.binaryFile.get().asFile
        linkTaskProp.get() == linkTask

        and:
        installDirProp.get().asFile == installTask.installDirectory.get().asFile
        installTaskProp.get() == installTask
    }

    def "adds tasks to assemble and strip an executable"() {
        def toolProvider = Stub(PlatformToolProvider)
        toolProvider.getExecutableName(_) >> { String p -> p + ".exe" }
        toolProvider.getExecutableSymbolFileName(_) >> { String p -> p + ".exe.pdb" }
        toolProvider.requiresDebugBinaryStripping() >> true

        def exeFileProp = project.objects.property(RegularFile)
        def debugExeFileProp = project.objects.property(RegularFile)
        def linkTaskProp = project.objects.property(LinkExecutable)
        def installDirProp = project.objects.property(Directory)
        def installTaskProp = project.objects.property(InstallExecutable)

        def executable = Stub(ConfigurableComponentWithExecutable)
        executable.name >> "windowsDebug"
        executable.names >> Names.of("windowsDebug")
        executable.debuggable >> true
        executable.optimized >> true
        executable.targetPlatform >> Stub(NativePlatformInternal)
        executable.toolChain >> Stub(NativeToolChainInternal)
        executable.platformToolProvider >> toolProvider
        executable.baseName >> Providers.of("test_app")
        executable.executableFile >> exeFileProp
        executable.debuggerExecutableFile >> debugExeFileProp
        executable.linkTask >> linkTaskProp
        executable.installDirectory >> installDirProp
        executable.installTask >> installTaskProp

        given:
        project.pluginManager.apply(NativeBasePlugin)
        project.components.add(executable)

        expect:
        def linkTask = project.tasks['linkWindowsDebug']
        linkTask instanceof LinkExecutable
        linkTask.binaryFile.get().asFile == projectDir.file("build/exe/windows/debug/test_app.exe")

        def stripTask = project.tasks['stripSymbolsWindowsDebug']
        stripTask instanceof StripSymbols
        stripTask.binaryFile.get().asFile == linkTask.binaryFile.get().asFile
        stripTask.outputFile.get().asFile == projectDir.file("build/exe/windows/debug/stripped/test_app.exe")

        def extractTask = project.tasks['extractSymbolsWindowsDebug']
        extractTask instanceof ExtractSymbols
        extractTask.binaryFile.get().asFile == linkTask.binaryFile.get().asFile
        extractTask.symbolFile.get().asFile == projectDir.file("build/exe/windows/debug/stripped/test_app.exe.pdb")

        def installTask = project.tasks['installWindowsDebug']
        installTask instanceof InstallExecutable
        installTask.sourceFile.get().asFile == stripTask.outputFile.get().asFile
        installTask.installDirectory.get().asFile == projectDir.file("build/install/windows/debug")

        and:
        exeFileProp.get().asFile == stripTask.outputFile.get().asFile
        debugExeFileProp.get().asFile == linkTask.binaryFile.get().asFile
        linkTaskProp.get() == linkTask

        and:
        installDirProp.get().asFile == installTask.installDirectory.get().asFile
        installTaskProp.get() == installTask
    }

    def "adds outgoing configuration for component with link usage"() {
        def component = Stub(ConfigurableComponentWithLinkUsage)
        component.name >> "debugWindows"
        component.names >> Names.of("debugWindows")
        component.implementationDependencies >> Stub(ConfigurationInternal)

        given:
        project.pluginManager.apply(NativeBasePlugin)
        project.components.add(component)

        expect:
        project.configurations['debugWindowsLinkElements']
    }

    def "adds outgoing configuration for component with runtime usage"() {
        def component = Stub(ConfigurableComponentWithRuntimeUsage)
        component.name >> "debugWindows"
        component.names >> Names.of("debugWindows")
        component.implementationDependencies >> Stub(ConfigurationInternal)

        given:
        project.pluginManager.apply(NativeBasePlugin)
        project.components.add(component)

        expect:
        project.configurations['debugWindowsRuntimeElements']
    }

    def "adds Maven publications for component with main publication"() {
        def usage1 = Stub(UsageContext)
        def artifact1 = Stub(PublishArtifact)
        artifact1.getFile() >> projectDir.file("artifact1")
        usage1.artifacts >> [artifact1]
        def variant1 = Stub(PublishableVariant)
        variant1.name >> "debug"
        variant1.usages >> [usage1]
        variant1.getCoordinates() >> new DefaultModuleVersionIdentifier("my.group", "test_app_debug", "1.2")

        def usage2 = Stub(UsageContext)
        def artifact2 = Stub(PublishArtifact)
        artifact2.getFile() >> projectDir.file("artifact1")
        usage2.artifacts >> [artifact2]
        def variant2 = Stub(PublishableVariant)
        variant2.name >> "release"
        variant2.usages >> [usage2]
        variant2.getCoordinates() >> new DefaultModuleVersionIdentifier("my.group", "test_app_release", "1.2")

        def doNotPublish = Stub(SoftwareComponentInternal)

        def mainVariant = Stub(TestVariant)
        mainVariant.name >> "main"
        mainVariant.variants >> [variant1, variant2, doNotPublish]

        def component = Stub(PublicationAwareComponent)
        component.mainPublication >> mainVariant
        component.baseName >> Providers.of('test_app')

        given:
        project.pluginManager.apply(NativeBasePlugin)
        project.pluginManager.apply(MavenPublishPlugin)
        project.components.add(component)
        project.group = "my.group"
        project.version = "1.2"

        expect:
        def publishing = project.publishing
        publishing.publications.size() == 3

        def main = publishing.publications.main
        main.groupId == 'my.group'
        main.artifactId == 'test_app'
        main.version == '1.2'
        main.artifacts.empty

        def debug = publishing.publications.debug
        debug.groupId == 'my.group'
        debug.artifactId == 'test_app_debug'
        debug.version == '1.2'
        debug.artifacts.size() == 1

        def release = publishing.publications.release
        release.groupId == 'my.group'
        release.artifactId == 'test_app_release'
        release.version == '1.2'
        release.artifacts.size() == 1
    }

    private ComponentWithOutputs binary(String name, String taskName) {
        def outputs = fileCollection(taskName)
        def binary = Stub(TestBinary)
        binary.name >> name
        binary.names >> Names.of(name)
        binary.outputs >> outputs
        return binary
    }

    private FileCollection fileCollection(String taskName) {
        def installTask = Stub(Task)
        installTask.name >> taskName
        def deps = Stub(TaskDependency)
        deps.getDependencies(_) >> [installTask]
        def outputs = Stub(FileCollection)
        outputs.buildDependencies >> deps
        return outputs
    }

    interface TestBinary extends ComponentWithOutputs, ComponentWithNames {
    }

    interface TestComponent extends ProductionComponent, ComponentWithBinaries {
    }

    interface TestVariant extends ComponentWithVariants, SoftwareComponentInternal {
    }

    interface PublishableVariant extends PublishableComponent, SoftwareComponentInternal {
    }
}
