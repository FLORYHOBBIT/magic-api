package org.ssssssss.magicapi.core.web;

import org.junit.jupiter.api.*;
import org.ssssssss.magicapi.core.config.*;
import org.ssssssss.magicapi.core.context.MagicUser;
import org.ssssssss.magicapi.core.exception.*;
import org.ssssssss.magicapi.core.interceptor.*;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MagicResourceLockPasswordTest {
    MagicConfiguration config;
    MagicResourceService previous, service;
    AuthorizationInterceptor auth;
    MagicHttpServletRequest request;
    MagicResourceController controller;
    MagicUser user = new MagicUser("operator", "operator", "token");

    @BeforeEach void setup() {
        config = new MagicConfiguration();
        previous = MagicConfiguration.getMagicResourceService();
        service = mock(MagicResourceService.class);
        config.setMagicResourceService(service);
        auth = mock(AuthorizationInterceptor.class);
        config.setAuthorizationInterceptor(auth);
        request = mock(MagicHttpServletRequest.class);
        when(request.getAttribute(Constants.ATTRIBUTE_MAGIC_USER)).thenReturn(user);
        when(service.file("one")).thenReturn(new MagicEntity());
        when(auth.requireLogin()).thenReturn(true);
        when(auth.allowVisit(eq(user), eq(request), any(Authorization.class), any(MagicEntity.class))).thenReturn(true);
        controller = new MagicResourceController(config);
    }
    @AfterEach void restore() { config.setMagicResourceService(previous); }

    void change(boolean locked, String password) {
        if (locked) controller.lock("one", password, request);
        else controller.unlock("one", password, request);
    }
    void noWrite() { verify(service, never()).lock(anyString()); verify(service, never()).unlock(anyString()); }
    void failsBoth(String password, int code) {
        for (boolean locked : new boolean[]{true, false}) {
            assertEquals(code, assertThrows(InvalidArgumentException.class, () -> change(locked, password)).getCode());
        }
        noWrite();
    }
    @Test void correctPasswordChangesBothStatesUsingSessionUsername() throws Exception {
        doReturn(user).when(auth).login("operator", "fixture-pass");
        change(true, "fixture-pass"); change(false, "fixture-pass");
        verify(service).lock("one"); verify(service).unlock("one");
        verify(auth, times(2)).login("operator", "fixture-pass");
    }
    @Test void missingPasswordCannotBypassConfirmation() throws Exception {
        failsBoth(null, 1041); failsBoth("", 1041);
        verify(auth, never()).login(anyString(), anyString());
    }
    @Test void wrongPasswordDoesNotChangeLockOrExpireLogin() throws Exception {
        when(auth.login(anyString(), anyString())).thenThrow(new MagicLoginException("bad"));
        failsBoth("wrong-fixture", 1042);
    }
    @Test void anotherAccountOrNullResultCannotConfirmCurrentAccount() throws Exception {
        when(auth.login(anyString(), anyString())).thenReturn(new MagicUser("u2", "other", "token"));
        failsBoth("fixture-pass", 1042);
        when(auth.login(anyString(), anyString())).thenReturn(null);
        failsBoth("fixture-pass", 1042);
    }
    @Test void guestAndPasswordlessModeFailClosed() {
        when(auth.allowVisit(any(), eq(request), any(Authorization.class), any(MagicEntity.class))).thenReturn(true);
        when(request.getAttribute(Constants.ATTRIBUTE_MAGIC_USER)).thenReturn(null);
        failsBoth("fixture-pass", 1040);
        when(request.getAttribute(Constants.ATTRIBUTE_MAGIC_USER)).thenReturn(user);
        when(auth.requireLogin()).thenReturn(false);
        failsBoth("fixture-pass", 1040);
    }
    @Test void passwordDoesNotGrantMissingLockPermission() throws Exception {
        when(auth.allowVisit(eq(user), eq(request), any(Authorization.class), any(MagicEntity.class))).thenReturn(false);
        failsBoth("fixture-pass", -10);
        verify(auth, never()).login(anyString(), anyString());
    }
    @Test void builtInLoginChecksActualConfiguredPassword() {
        config.setAuthorizationInterceptor(new DefaultAuthorizationInterceptor("operator", "fixture-pass"));
        failsBoth("wrong-fixture", 1042);
        change(true, "fixture-pass"); change(false, "fixture-pass");
        verify(service).lock("one"); verify(service).unlock("one");
    }
    @Test void folderEndpointsRequirePasswordAndDelegatePerScriptPermissions() throws Exception {
        org.ssssssss.magicapi.core.model.Group group = new org.ssssssss.magicapi.core.model.Group();
        when(service.getGroup("folder")).thenReturn(group);
        when(auth.allowVisit(eq(user), eq(request), any(Authorization.class), eq(group))).thenReturn(true);
        assertEquals(1041, assertThrows(InvalidArgumentException.class, () -> controller.lockFolder("folder", null, request)).getCode());
        when(auth.login(anyString(), anyString())).thenThrow(new MagicLoginException("wrong"));
        assertEquals(1042, assertThrows(InvalidArgumentException.class, () -> controller.unlockFolder("folder", "wrong", request)).getCode());
        verify(service, never()).lockGroup(anyString(), anyBoolean(), any());
        doReturn(user).when(auth).login("operator", "fixture-pass");
        when(service.lockGroup(eq("folder"), anyBoolean(), any())).thenAnswer(invocation -> {
            java.util.function.Predicate<MagicEntity> permission = invocation.getArgument(2);
            assertTrue(permission.test(new MagicEntity()));
            return java.util.Collections.emptyMap();
        });
        controller.lockFolder("folder", "fixture-pass", request);
        controller.unlockFolder("folder", "fixture-pass", request);
        verify(service).lockGroup(eq("folder"), eq(true), any()); verify(service).lockGroup(eq("folder"), eq(false), any());
    }
}
