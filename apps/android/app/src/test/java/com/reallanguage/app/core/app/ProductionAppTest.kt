package com.reallanguage.app.core.app

import com.reallanguage.app.core.navigation.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductionAppTest {
    @Test fun everyConfirmedRootRouteHasOneProductionSurface() {
        assertEquals(ProductionSurface.LOGIN, productionSurface(AppRoute.Login))
        assertEquals(ProductionSurface.INITIAL_SETTINGS, productionSurface(AppRoute.InitialSettings))
        assertEquals(ProductionSurface.HOME, productionSurface(AppRoute.Home))
        assertEquals(ProductionSurface.CONNECTION_ERROR, productionSurface(AppRoute.ConnectionError))
    }
}
