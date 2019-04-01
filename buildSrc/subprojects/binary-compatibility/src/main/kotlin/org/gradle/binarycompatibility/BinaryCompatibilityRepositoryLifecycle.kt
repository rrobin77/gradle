/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.binarycompatibility

import me.champeau.gradle.japicmp.report.PostProcessViolationsRule
import me.champeau.gradle.japicmp.report.SetupRule
import me.champeau.gradle.japicmp.report.ViolationCheckContext
import me.champeau.gradle.japicmp.report.ViolationCheckContextWithViolations

import java.io.File
import java.util.function.Supplier


class BinaryCompatibilityRepositorySetupRule(private val params: Map<String, Any>) : SetupRule {

    companion object {
        const val REPOSITORY_CONTEXT_KEY = "binaryCompatibilityRepository"
    }

    object Params {

        /**
         * Parameter is a `Set<String>`.
         */
        val currentSourceRoots = "currentSourceRoots"

        /**
         * Parameter is a Supplier<Set<String>>
         */
        val currentClasspathSupplier = "currentClasspathSupplier"
    }

    override fun execute(context: ViolationCheckContext) {
        (context.userData as MutableMap<String, Any?>)[REPOSITORY_CONTEXT_KEY] = BinaryCompatibilityRepository.openRepositoryFor(
            param(Params.currentSourceRoots),
            paramSupplier(Params.currentClasspathSupplier)
        )
    }

    private
    fun param(name: String): List<File> =
        (params[name] as? Set<String>)?.map(::File) ?: emptyList()

    private
    fun paramSupplier(name: String): List<File> =
        (params[name] as? Supplier<Set<String>>)?.get()?.map(::File) ?: emptyList()
}


class BinaryCompatibilityRepositoryPostProcessRule : PostProcessViolationsRule {
    override fun execute(context: ViolationCheckContextWithViolations) {
        (context.userData[BinaryCompatibilityRepositorySetupRule.REPOSITORY_CONTEXT_KEY] as BinaryCompatibilityRepository).close()
        (context.userData as MutableMap<String, Any?>)[BinaryCompatibilityRepositorySetupRule.REPOSITORY_CONTEXT_KEY] = null
    }
}
