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

package org.gradle.instantexecution.support

import org.gradle.api.Task
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.file.FileCollectionFactory
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory
import org.gradle.api.internal.initialization.ClassLoaderIds
import org.gradle.api.internal.initialization.ClassLoaderScope
import org.gradle.api.internal.initialization.loadercache.ClassLoaderCache
import org.gradle.api.internal.project.IProjectFactory
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.project.ProjectStateRegistry
import org.gradle.initialization.ClassLoaderScopeRegistry
import org.gradle.initialization.DefaultProjectDescriptor
import org.gradle.initialization.DefaultSettings
import org.gradle.instantexecution.InstantExecution
import org.gradle.internal.build.BuildState
import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.file.PathToFileResolver
import org.gradle.internal.reflect.Instantiator
import org.gradle.util.Path
import java.io.File


class InstantExecutionHost internal constructor(
    private val gradle: GradleInternal
) : InstantExecution.Host {

    private
    val classLoaderScopeRegistry = getService(ClassLoaderScopeRegistry::class.java)

    private
    val projectFactory = getService(IProjectFactory::class.java)

    private
    val projectDescriptorRegistry
        get() = (gradle.settings as DefaultSettings).projectDescriptorRegistry

    override val stateSerializer by lazy {
        DefaultStateSerializer(
            getService(DirectoryFileTreeFactory::class.java),
            getService(FileCollectionFactory::class.java),
            getService(FileResolver::class.java),
            getService(Instantiator::class.java)
        )
    }

    override val scheduledTasks: List<Task>
        get() = gradle.taskGraph.allTasks

    private
    val coreAndPluginsScope: ClassLoaderScope
        get() = classLoaderScopeRegistry.coreAndPluginsScope

    override fun <T> getService(serviceType: Class<T>): T =
        gradle.services.get(serviceType)

    override fun getSystemProperty(propertyName: String) =
        gradle.startParameter.systemPropertiesArgs[propertyName]

    override fun scheduleTasks(tasks: Iterable<Task>) =
        gradle.taskGraph.addEntryTasks(tasks)

    override fun createProject(path: String): ProjectInternal {
        val projectPath = Path.path(path)
        val parentPath = projectPath.parent
        val name = projectPath.name
        val projectDescriptor = DefaultProjectDescriptor(
            getProjectDescriptor(parentPath),
            name ?: "instant-execution",
            File(".").absoluteFile,
            projectDescriptorRegistry,
            getService(PathToFileResolver::class.java)
        )
        return projectFactory.createProject(
            projectDescriptor,
            getProject(parentPath),
            gradle,
            coreAndPluginsScope,
            coreAndPluginsScope
        )
    }

    private
    fun getProject(parentPath: Path?) =
        parentPath?.let { gradle.rootProject.project(it.path) }

    private
    fun getProjectDescriptor(parentPath: Path?): DefaultProjectDescriptor? =
        parentPath?.let { projectDescriptorRegistry.getProject(it.path) }

    override fun classLoaderFor(classPath: ClassPath): ClassLoader =
        getService(ClassLoaderCache::class.java).get(
            ClassLoaderIds.buildScript("instant-execution", "run"),
            classPath,
            coreAndPluginsScope.exportClassLoader,
            null
        )

    override fun dependenciesOf(task: Task): Set<Task> =
        gradle.taskGraph.getDependencies(task)

    override fun getProject(projectPath: String): ProjectInternal =
        gradle.rootProject.project(projectPath)

    override fun registerProjects() =
        getService(ProjectStateRegistry::class.java).registerProjects(getService(BuildState::class.java))
}
