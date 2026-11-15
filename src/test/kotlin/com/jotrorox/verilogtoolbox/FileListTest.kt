package com.jotrorox.verilogtoolbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class FileListTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val root: Path get() = folder.root.toPath().toAbsolutePath().normalize()

    /** Writes [text] to [name] below the root, creating its directory. */
    private fun write(name: String, text: String = ""): Path {
        val file = root.resolve(name)
        Files.createDirectories(file.parent)
        return Files.writeString(file, text)
    }

    /** Parses [text] as a file list in the root, with `$HDL` set to `rtl`. */
    private fun parse(text: String): FileList =
        FileList.parse(write("design.f", text), root) { name -> "rtl".takeIf { name == "HDL" } }

    private fun paths(vararg names: String): List<Path> = names.map { root.resolve(it) }

    @Test
    fun sourcesAreResolvedAgainstTheGivenDirectory() {
        val list = FileList.parse(write("lists/design.f", "rtl/a.v\n../shared/b.sv /abs/c.v\n"), root)
        assertEquals(paths("rtl/a.v").plus(listOf(root.resolveSibling("shared/b.sv"), Path.of("/abs/c.v"))), list.sources)
        assertEquals(emptyList<String>(), list.errors)
    }

    @Test
    fun includeDirectoriesInEverySpelling() = assertEquals(
        paths("a", "b", "c", "d", "e", "f", "g", "h"),
        parse("-I a -Ib -I=c --include-directory d --incdir=e +incdir+f+g -I h,a").includeDirectories,
    )

    @Test
    fun definesInEverySpelling() = assertEquals(
        listOf("A", "B=1", "C=x,y", "D", "E=2", "F"),
        parse("-D A -DB=1 --define-macro C=x,y --define-macro=D +define+E=2+F").defines,
    )

    @Test
    fun librariesAndTops() {
        val list = parse("-y lib --libdir cells,more -v prims.v --libfile=io.v +libext+.vp+sv -Y vlib --top tb --top=a,b")
        assertEquals(paths("lib", "cells", "more"), list.libraryDirectories)
        assertEquals(paths("prims.v", "io.v"), list.libraryFiles)
        assertEquals(listOf(".vp", ".sv", ".vlib"), list.libraryExtensions)
        assertEquals(listOf("tb", "a", "b"), list.topModules)
    }

    @Test
    fun commentsAreSkipped() = assertEquals(
        paths("a.v", "b.v", "c//d.v", "e.v"),
        parse("a.v // not.v\n# not.v\nb.v# not.v\n/* not.v\n not.v */ c//d.v\n// not.v\ne.v").sources,
    )

    @Test
    fun quotesAndEscapesGroupWords() = assertEquals(
        paths("my rtl/a.v", "my rtl/b.v", "my rtl/c.v", "say \"hi\".v", "\$HDL/d.v"),
        parse("\"my rtl/a.v\" 'my rtl/b.v' my\\ rtl/c.v \"say \\\"hi\\\".v\" '\$HDL/d.v'").sources,
    )

    @Test
    fun environmentVariablesAreExpanded() {
        val list = parse("\$HDL/a.v \${HDL}/b.v \$(HDL)/c.v \"\$HDL/d.v\" \$UNSET -I\$HDL/inc")
        assertEquals(paths("rtl/a.v", "rtl/b.v", "rtl/c.v", "rtl/d.v"), list.sources)
        assertEquals(paths("rtl/inc"), list.includeDirectories)
    }

    @Test
    fun unknownOptionsAreCollectedWithTheirValues() {
        val list = parse("--std 1800-2017 -Wno-foo --timescale=1ns/1ps +notimingchecks -G W=8 --strange a.v")
        assertEquals(listOf("--std", "-W", "--timescale", "+notimingchecks", "-G", "--strange"), list.ignored)
        assertEquals(paths("a.v"), list.sources)
        assertEquals(emptyList<String>(), list.errors)
    }

    @Test
    fun nestedListsResolveTheirPathsLikeSlang() {
        write("sub/cwd.f", "from_cwd.v -I inc")
        write("sub/own.f", "from_own.v -I inc -f nested.f")
        write("sub/nested.f", "nested.v")
        val list = parse("top.v -f sub/cwd.f -F sub/own.f")
        // -f keeps the directory of the list naming it; -F moves to its own, also for what it nests.
        assertEquals(paths("top.v", "from_cwd.v", "sub/from_own.v", "sub/nested.v"), list.sources)
        assertEquals(paths("inc", "sub/inc"), list.includeDirectories)
        assertEquals(emptyList<String>(), list.errors)
    }

    @Test
    fun recursiveListsAreAnError() {
        write("a.f", "a.v -f b.f")
        write("b.f", "b.v -f a.f")
        val list = FileList.parse(root.resolve("a.f"), root)
        assertEquals(paths("a.v", "b.v"), list.sources)
        assertEquals(1, list.errors.size)
        assertTrue(list.errors.single(), "includes itself" in list.errors.single())
    }

    @Test
    fun missingListsAndValuesAreErrors() {
        assertEquals(1, parse("-f missing.f a.v").errors.size)
        assertEquals(1, parse("a.v -I").errors.size)
        assertEquals(1, FileList.parse(root.resolve("missing.f"), root).errors.size)
    }

    @Test
    fun patternsAreExpanded() {
        write("rtl/a.sv")
        write("rtl/b.sv")
        write("rtl/notes.txt")
        write("rtl/core/alu.sv")
        write("rtl/core/deep/mul.sv")
        val list = parse("rtl/*.sv rtl/.../?u?.sv rtl/core/ -I rtl/... -y rtl/c*")
        assertEquals(
            paths("rtl/a.sv", "rtl/b.sv", "rtl/core/deep/mul.sv", "rtl/core/alu.sv"),
            list.sources,
        )
        assertEquals(paths("rtl", "rtl/core", "rtl/core/deep"), list.includeDirectories)
        assertEquals(paths("rtl/core"), list.libraryDirectories)
    }

    @Test
    fun relativePathsStartWhereSlangServerStartsThem() {
        val file = write("lists/design.f")
        val home = root.resolve("home")
        assertEquals(root.resolve("lists"), FileList.baseDirectory(file, null, home))
        assertEquals(root, FileList.baseDirectory(file, root, home))
        write(".slang/server.json", "{ \"buildRelativePaths\": true }")
        assertEquals(root.resolve("lists"), FileList.baseDirectory(file, root, home))
        // The local configuration is read last and wins.
        write(".slang/local/server.json", "{ \"buildRelativePaths\": false }")
        assertEquals(root, FileList.baseDirectory(file, root, home))
    }
}
