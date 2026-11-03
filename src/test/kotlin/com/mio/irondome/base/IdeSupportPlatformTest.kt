package com.mio.irondome.base

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class IdeSupportPlatformTest : BasePlatformTestCase() {

    fun testTheTestedIdeHasEverythingTheMechanismsUse() {
        assertEmpty(IdeSupport.missing.entries)
        assertFalse(IdeSupport.isOlder(IdeSupport.ideBuild, IdeSupport.TESTED_BUILD))
    }
}
