/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.auth;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.Iterables;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ConsistencyLevel;
import org.assertj.core.api.Assertions;

import static org.apache.cassandra.auth.AuthTestUtils.ALL_ROLES;
import static org.apache.cassandra.auth.AuthTestUtils.ROLE_A;
import static org.apache.cassandra.auth.AuthTestUtils.ROLE_B;
import static org.apache.cassandra.auth.AuthTestUtils.ROLE_B_1;
import static org.apache.cassandra.auth.AuthTestUtils.ROLE_B_2;
import static org.apache.cassandra.auth.AuthTestUtils.ROLE_C;
import static org.apache.cassandra.auth.AuthTestUtils.getRolesReadCount;
import static org.apache.cassandra.auth.AuthTestUtils.grantRolesTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RolesTest
{

    @BeforeClass
    public static void setupClass()
    {
        SchemaLoader.prepareServer();
        IRoleManager roleManager = new AuthTestUtils.LocalCassandraRoleManager();
        SchemaLoader.setupAuth(roleManager,
                               new AuthTestUtils.LocalPasswordAuthenticator(),
                               new AuthTestUtils.LocalCassandraAuthorizer(),
                               new AuthTestUtils.LocalCassandraNetworkAuthorizer(),
                               new AuthTestUtils.LocalCassandraCIDRAuthorizer());

        for (RoleResource role : ALL_ROLES)
            roleManager.createRole(AuthenticatedUser.ANONYMOUS_USER, role, new RoleOptions());
        grantRolesTo(roleManager, ROLE_A, ROLE_B, ROLE_C);

        RoleOptions roleOptions = new RoleOptions();
        roleOptions.setOption(IRoleManager.Option.SUPERUSER, true);
        RoleResource testSuperUser = RoleResource.role("testSuperuser");
        roleManager.createRole(AuthenticatedUser.ANONYMOUS_USER, testSuperUser, roleOptions);
        grantRolesTo(roleManager, ROLE_B_1, testSuperUser);
        grantRolesTo(roleManager, ROLE_B_2, ROLE_B_1);

        roleManager.setup();
        AuthCacheService.initializeAndRegisterCaches();
    }

    @Test
    public void superuserStatusIsCached()
    {
        boolean hasSuper = Roles.hasSuperuserStatus(ROLE_A);
        long count = getRolesReadCount();

        assertEquals(hasSuper, Roles.hasSuperuserStatus(ROLE_A));
        assertEquals(count, getRolesReadCount());
    }

    @Test
    public void loginPrivilegeIsCached()
    {
        boolean canLogin = Roles.canLogin(ROLE_A);
        long count = getRolesReadCount();

        assertEquals(canLogin, Roles.canLogin(ROLE_A));
        assertEquals(count, getRolesReadCount());
    }

    @Test
    public void grantedRoleDetailsAreCached()
    {
        Iterable<Role> granted = Roles.getRoleDetails(ROLE_A);
        long count = getRolesReadCount();

        assertTrue(Iterables.elementsEqual(granted, Roles.getRoleDetails(ROLE_A)));
        assertEquals(count, getRolesReadCount());
    }

    @Test
    public void grantedRoleResourcesAreCached()
    {
        Set<RoleResource> granted = Roles.getRoles(ROLE_A);
        long count = getRolesReadCount();

        assertEquals(granted, Roles.getRoles(ROLE_A));
        assertEquals(count, getRolesReadCount());
    }

    @Test
    public void confirmSuperUserConsistency()
    {
        // Confirm special treatment of superuser
        ConsistencyLevel readLevel = CassandraRoleManager.consistencyForRoleRead(CassandraRoleManager.DEFAULT_SUPERUSER_NAME);
        Assert.assertEquals(CassandraRoleManager.DEFAULT_SUPERUSER_CONSISTENCY_LEVEL, readLevel);

        ConsistencyLevel writeLevel = CassandraRoleManager.consistencyForRoleWrite(CassandraRoleManager.DEFAULT_SUPERUSER_NAME);
        Assert.assertEquals(CassandraRoleManager.DEFAULT_SUPERUSER_CONSISTENCY_LEVEL, writeLevel);

        // Confirm standard config-based treatment of non
        ConsistencyLevel nonPrivReadLevel = CassandraRoleManager.consistencyForRoleRead("non-privilaged");
        Assert.assertEquals(nonPrivReadLevel, DatabaseDescriptor.getAuthReadConsistencyLevel());

        ConsistencyLevel nonPrivWriteLevel = CassandraRoleManager.consistencyForRoleWrite("non-privilaged");
        Assert.assertEquals(nonPrivWriteLevel, DatabaseDescriptor.getAuthWriteConsistencyLevel());
    }

    @Test
    public void testSuperUsers()
    {
        Assert.assertEquals(new HashSet<>(Arrays.asList("testSuperuser", "role_b_1", "role_b_2")),
                            Roles.getAllRoles(Roles::hasSuperuserStatus)
                                 .stream()
                                 .map(RoleResource::getRoleName)
                                 .collect(Collectors.toSet()));
    }

    @Test
    public void testNonexistentRoleCantLogin()
    {
        // There can be a reference to a nonexistent role (that has been removed from the cache and the system table)
        // via the native transport connection state, make sure there's no NPE on canLogin check
        AuthenticatedUser nonexistent = new AuthenticatedUser("nonexistent");
        Assertions.assertThat(nonexistent.canLogin()).isFalse();
    }
}
