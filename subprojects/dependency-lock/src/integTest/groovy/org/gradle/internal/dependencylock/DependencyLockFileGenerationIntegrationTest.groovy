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

package org.gradle.internal.dependencylock

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.test.fixtures.file.TestFile

import static org.gradle.initialization.StartParameterBuildOptions.DependencyLockOption
import static org.gradle.internal.dependencylock.fixtures.DependencyLockFixture.*

class DependencyLockFileGenerationIntegrationTest extends AbstractIntegrationSpec {

    private static final String MYCONF_CUSTOM_CONFIGURATION = 'myConf'

    TestFile lockFile
    TestFile sha1File

    def setup() {
        lockFile = file('gradle/dependencies.lock')
        sha1File = file('gradle/dependencies.lock.sha1')
    }

    def "does not write lock file if no dependencies were resolved"() {
        given:
        buildFile << mavenRepository(mavenRepo)
        buildFile << customConfigurations(MYCONF_CUSTOM_CONFIGURATION)
        buildFile << copyLibsTask(MYCONF_CUSTOM_CONFIGURATION)

        when:
        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        !lockFile.exists()
        !sha1File.exists()
    }

    def "does not write lock file if all dependencies failed to be resolved"() {
        given:
        buildFile << mavenRepository(mavenRepo)
        buildFile << customConfigurations(MYCONF_CUSTOM_CONFIGURATION)
        buildFile << """
            dependencies {
                myConf 'foo:bar:1.5'
            }
        """
        buildFile << copyLibsTask(MYCONF_CUSTOM_CONFIGURATION)

        when:
        failsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        !lockFile.exists()
        !sha1File.exists()
    }

    def "can create locks for dependencies with concrete version"() {
        given:
        mavenRepo.module('foo', 'bar', '1.5').publish()

        buildFile << mavenRepository(mavenRepo)
        buildFile << customConfigurations(MYCONF_CUSTOM_CONFIGURATION)
        buildFile << """
            dependencies {
                myConf 'foo:bar:1.5'
            }
        """
        buildFile << copyLibsTask(MYCONF_CUSTOM_CONFIGURATION)

        when:
        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":","configurations":[{"name":"myConf","dependencies":[{"requestedVersion":"1.5","moduleId":"foo:bar","lockedVersion":"1.5"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == 'c6fb6b274c5398f8de0f2fde16ea2302ec8b9c4b'
    }

    def "can write locks if at least one dependency is resolvable"() {
        given:
        mavenRepo.module('foo', 'bar', '1.5').publish()

        buildFile << mavenRepository(mavenRepo)
        buildFile << customConfigurations(MYCONF_CUSTOM_CONFIGURATION)
        buildFile << """
            dependencies {
                myConf 'foo:bar:1.5'
                myConf 'does.not:exist:1.2.3'
            }
        """
        buildFile << copyLibsTask(MYCONF_CUSTOM_CONFIGURATION)

        when:
        failsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":","configurations":[{"name":"myConf","dependencies":[{"requestedVersion":"1.5","moduleId":"foo:bar","lockedVersion":"1.5"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == 'c6fb6b274c5398f8de0f2fde16ea2302ec8b9c4b'
    }

    def "can create locks for all supported formats of dynamic dependencies"() {
        given:
        mavenRepo.module('foo', 'bar', '1.3').publish()
        mavenRepo.module('org', 'gradle', '7.5').publish()
        mavenRepo.module('my', 'prod', '3.2.1').publish()
        mavenRepo.module('dep', 'range', '1.7.1').publish()

        buildFile << mavenRepository(mavenRepo)
        buildFile << customConfigurations(MYCONF_CUSTOM_CONFIGURATION)
        buildFile << """
            dependencies {
                myConf 'foo:bar:1.+'
                myConf 'org:gradle:+'
                myConf 'my:prod:latest.release'
                myConf 'dep:range:[1.0,2.0]'
            }
        """
        buildFile << copyLibsTask(MYCONF_CUSTOM_CONFIGURATION)

        when:
        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":","configurations":[{"name":"myConf","dependencies":[{"requestedVersion":"1.+","moduleId":"foo:bar","lockedVersion":"1.3"},{"requestedVersion":"+","moduleId":"org:gradle","lockedVersion":"7.5"},{"requestedVersion":"latest.release","moduleId":"my:prod","lockedVersion":"3.2.1"},{"requestedVersion":"[1.0,2.0]","moduleId":"dep:range","lockedVersion":"1.7.1"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == 'b4be110823c3d0cff395069a0b3dac006338b43b'
    }

    def "only creates locks for resolved dependencies"() {
        given:
        mavenRepo.module('foo', 'bar', '1.3').publish()
        mavenRepo.module('org', 'gradle', '7.5').publish()

        buildFile << mavenRepository(mavenRepo)
        buildFile << customConfigurations(MYCONF_CUSTOM_CONFIGURATION, 'unresolved')
        buildFile << """
            dependencies {
                myConf 'foo:bar:1.+'
                unresolved 'org:gradle:+'
            }
        """
        buildFile << copyLibsTask(MYCONF_CUSTOM_CONFIGURATION)

        when:
        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":","configurations":[{"name":"myConf","dependencies":[{"requestedVersion":"1.+","moduleId":"foo:bar","lockedVersion":"1.3"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == 'b78a4552f1f47012247209aee86bd566f85b4f6'
    }

    def "can create locks for all multiple resolved configurations"() {
        given:
        mavenRepo.module('foo', 'bar', '1.3').publish()
        mavenRepo.module('org', 'gradle', '7.5').publish()
        mavenRepo.module('my', 'prod', '3.2.1').publish()

        buildFile << mavenRepository(mavenRepo)
        buildFile << customConfigurations('a', 'b', 'c')
        buildFile << """
            dependencies {
                a 'foo:bar:1.+'
                b 'org:gradle:7.5'
                c 'my:prod:latest.release'
            }
        """
        buildFile << copyLibsTask('a', 'b', 'c')

        when:
        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":","configurations":[{"name":"a","dependencies":[{"requestedVersion":"1.+","moduleId":"foo:bar","lockedVersion":"1.3"}]},{"name":"b","dependencies":[{"requestedVersion":"7.5","moduleId":"org:gradle","lockedVersion":"7.5"}]},{"name":"c","dependencies":[{"requestedVersion":"latest.release","moduleId":"my:prod","lockedVersion":"3.2.1"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == 'e58f71cf01bba1c6293bd167c03569458234caad'
    }

    def "can create locks for first-level and transitive resolved dependencies"() {
        given:
        def fooThirdModule = mavenRepo.module('foo', 'third', '1.5').publish()
        def fooSecondModule = mavenRepo.module('foo', 'second', '1.6.7').dependsOn(fooThirdModule).publish()
        mavenRepo.module('foo', 'first', '1.5').dependsOn(fooSecondModule).publish()
        def barThirdModule = mavenRepo.module('bar', 'third', '2.5').publish()
        def barSecondModule = mavenRepo.module('bar', 'second', '2.6.7').dependsOn(barThirdModule).publish()
        mavenRepo.module('bar', 'first', '2.5').dependsOn(barSecondModule).publish()

        buildFile << mavenRepository(mavenRepo)
        buildFile << customConfigurations(MYCONF_CUSTOM_CONFIGURATION)
        buildFile << """
            dependencies {
                myConf 'foo:first:1.5'
                myConf 'bar:first:2.+'
            }
        """
        buildFile << copyLibsTask(MYCONF_CUSTOM_CONFIGURATION)

        when:
        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":","configurations":[{"name":"myConf","dependencies":[{"requestedVersion":"1.5","moduleId":"foo:first","lockedVersion":"1.5"},{"requestedVersion":"1.6.7","moduleId":"foo:second","lockedVersion":"1.6.7"},{"requestedVersion":"1.5","moduleId":"foo:third","lockedVersion":"1.5"},{"requestedVersion":"2.+","moduleId":"bar:first","lockedVersion":"2.5"},{"requestedVersion":"2.6.7","moduleId":"bar:second","lockedVersion":"2.6.7"},{"requestedVersion":"2.5","moduleId":"bar:third","lockedVersion":"2.5"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == '7d9d3f8005d4e644ea45b173fcf17f5410eda6a0'
    }

    def "can create lock for conflict-resolved dependency"() {
        given:
        def fooSecondModule = mavenRepo.module('foo', 'second', '1.6.7').publish()
        mavenRepo.module('foo', 'first', '1.5').dependsOn(fooSecondModule).publish()
        mavenRepo.module('foo', 'second', '1.9').publish()

        buildFile << mavenRepository(mavenRepo)
        buildFile << customConfigurations(MYCONF_CUSTOM_CONFIGURATION)
        buildFile << """
            dependencies {
                myConf 'foo:first:1.5'
                myConf 'foo:second:1.9'
            }
        """
        buildFile << copyLibsTask(MYCONF_CUSTOM_CONFIGURATION)

        when:
        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":","configurations":[{"name":"myConf","dependencies":[{"requestedVersion":"1.5","moduleId":"foo:first","lockedVersion":"1.5"},{"requestedVersion":"1.9","moduleId":"foo:second","lockedVersion":"1.9"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == '1f8f0335a029277514668ad9a789815f25588663'
    }

    def "only creates locks for resolvable configurations"() {
        given:
        mavenRepo.module('foo', 'bar', '1.5').publish()
        mavenRepo.module('org', 'gradle', '7.5').publish()

        buildFile << mavenRepository(mavenRepo)
        buildFile << """
            apply plugin: 'java-library'

            dependencies {
                api 'foo:bar:1.5'
                implementation 'org:gradle:7.5'
            }
        """
        buildFile << copyLibsTask('compileClasspath')

        when:
        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":","configurations":[{"name":"compileClasspath","dependencies":[{"requestedVersion":"1.5","moduleId":"foo:bar","lockedVersion":"1.5"},{"requestedVersion":"7.5","moduleId":"org:gradle","lockedVersion":"7.5"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == '91871c1a3ddd08f004bf92ed68a2ff91fa70049f'
    }

    def "can create locks for multi-project builds"() {
        given:
        mavenRepo.module('my', 'dep', '1.5').publish()
        mavenRepo.module('foo', 'bar', '2.3.1').publish()
        mavenRepo.module('other', 'company', '5.2').publish()

        buildFile << """
            subprojects {
                ${mavenRepository(mavenRepo)}
            }

            project(':a') {
                ${customConfigurations('conf1')}
    
                dependencies {
                    conf1 'my:dep:1.5'
                }

                ${copyLibsTask('conf1')}
            }

            project(':b') {
                ${customConfigurations('conf2')}

                dependencies {
                    conf2 'foo:bar:2.3.1'
                }

                ${copyLibsTask('conf2')}
            }

            project(':c') {
                ${customConfigurations('conf3')}

                dependencies {
                    conf3 'other:company:5.2'
                }

                ${copyLibsTask('conf3')}
            }
        """
        settingsFile << "include 'a', 'b', 'c'"

        when:
        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":a","configurations":[{"name":"conf1","dependencies":[{"requestedVersion":"1.5","moduleId":"my:dep","lockedVersion":"1.5"}]}]},{"path":":b","configurations":[{"name":"conf2","dependencies":[{"requestedVersion":"2.3.1","moduleId":"foo:bar","lockedVersion":"2.3.1"}]}]},{"path":":c","configurations":[{"name":"conf3","dependencies":[{"requestedVersion":"5.2","moduleId":"other:company","lockedVersion":"5.2"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == 'a446b9b74284f03d9dc606973ce5caf12f98e244'
    }

    def "subsequent builds do not recreate lock file for unchanged dependencies"() {
        given:
        mavenRepo.module('foo', 'bar', '1.5').publish()

        buildFile << mavenRepository(mavenRepo)
        buildFile << customConfigurations(MYCONF_CUSTOM_CONFIGURATION)
        buildFile << """
            dependencies {
                myConf 'foo:bar:1.5'
            }
        """
        buildFile << copyLibsTask(MYCONF_CUSTOM_CONFIGURATION)

        when:
        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        result.assertTasksExecuted(COPY_LIBS_TASK_PATH)
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":","configurations":[{"name":"myConf","dependencies":[{"requestedVersion":"1.5","moduleId":"foo:bar","lockedVersion":"1.5"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == 'c6fb6b274c5398f8de0f2fde16ea2302ec8b9c4b'

        when:
        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        result.assertTaskSkipped(COPY_LIBS_TASK_PATH)
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":","configurations":[{"name":"myConf","dependencies":[{"requestedVersion":"1.5","moduleId":"foo:bar","lockedVersion":"1.5"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == 'c6fb6b274c5398f8de0f2fde16ea2302ec8b9c4b'
    }

    def "recreates lock file for newly declared and resolved dependencies"() {
        given:
        mavenRepo.module('foo', 'bar', '1.5').publish()
        mavenRepo.module('org', 'gradle', '7.5').publish()

        buildFile << mavenRepository(mavenRepo)
        buildFile << customConfigurations(MYCONF_CUSTOM_CONFIGURATION)
        buildFile << """
            dependencies {
                myConf 'foo:bar:1.5'
            }
        """
        buildFile << copyLibsTask(MYCONF_CUSTOM_CONFIGURATION)

        when:
        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        result.assertTasksExecuted(COPY_LIBS_TASK_PATH)
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":","configurations":[{"name":"myConf","dependencies":[{"requestedVersion":"1.5","moduleId":"foo:bar","lockedVersion":"1.5"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == 'c6fb6b274c5398f8de0f2fde16ea2302ec8b9c4b'

        when:
        buildFile << """
            dependencies {
                myConf 'org:gradle:7.5'
            }
        """

        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        result.assertTasksExecuted(COPY_LIBS_TASK_PATH)
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":","configurations":[{"name":"myConf","dependencies":[{"requestedVersion":"1.5","moduleId":"foo:bar","lockedVersion":"1.5"},{"requestedVersion":"7.5","moduleId":"org:gradle","lockedVersion":"7.5"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == '56d3c6ea5f8d353566d0f5c9c92b447b4c01217e'
    }

    def "recreates lock file for removed, resolved dependencies"() {
        given:
        mavenRepo.module('foo', 'bar', '1.5').publish()
        mavenRepo.module('org', 'gradle', '7.5').publish()

        buildFile << mavenRepository(mavenRepo)
        buildFile << customConfigurations(MYCONF_CUSTOM_CONFIGURATION)
        buildFile << """
            dependencies {
                myConf 'foo:bar:1.5'
                myConf 'org:gradle:7.5'
            }
        """
        buildFile << copyLibsTask(MYCONF_CUSTOM_CONFIGURATION)

        when:
        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        result.assertTasksExecuted(COPY_LIBS_TASK_PATH)
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":","configurations":[{"name":"myConf","dependencies":[{"requestedVersion":"1.5","moduleId":"foo:bar","lockedVersion":"1.5"},{"requestedVersion":"7.5","moduleId":"org:gradle","lockedVersion":"7.5"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == '56d3c6ea5f8d353566d0f5c9c92b447b4c01217e'

        when:
        buildFile.text = mavenRepository(mavenRepo) + customConfigurations(MYCONF_CUSTOM_CONFIGURATION) + """
            dependencies {
                myConf 'foo:bar:1.5'
            }
        """ + copyLibsTask(MYCONF_CUSTOM_CONFIGURATION)

        succeedsWithEnabledDependencyLocking(COPY_LIBS_TASK_NAME)

        then:
        result.assertTasksExecuted(COPY_LIBS_TASK_PATH)
        lockFile.text == '{"lockFileVersion":"1.0","projects":[{"path":":","configurations":[{"name":"myConf","dependencies":[{"requestedVersion":"1.5","moduleId":"foo:bar","lockedVersion":"1.5"}]}]}],"_comment":"This is an auto-generated file and is not meant to be edited manually!"}'
        sha1File.text == 'c6fb6b274c5398f8de0f2fde16ea2302ec8b9c4b'
    }

    private void succeedsWithEnabledDependencyLocking(String... tasks) {
        withEnabledDependencyLocking()
        succeeds(tasks)
    }

    private void failsWithEnabledDependencyLocking(String... tasks) {
        withEnabledDependencyLocking()
        fails(tasks)
    }

    private void withEnabledDependencyLocking() {
        args("--$DependencyLockOption.LONG_OPTION")
    }
}
