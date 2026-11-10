/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import io.axoniq.framework.postgresql.PostgresqlEventStorageEngine
import io.axoniq.framework.postgresql.SchemaInitialization
import org.axonframework.common.configuration.AxonConfiguration
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer
import org.axonframework.messaging.eventhandling.conversion.EventConverter
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers(disabledWithoutDocker = true)
class PostgresIncrementalSourceBoundaryTest : IncrementalSourceBoundaryContract() {

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
    }

    override fun configure(): AxonConfiguration {
        val postgresDataSource = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
        // The engine commits its own transactions, as on a pool configured without auto-commit.
        val dataSource = object : DataSource by postgresDataSource {
            override fun getConnection(): Connection = postgresDataSource.connection.apply { autoCommit = false }
        }
        return EventSourcingConfigurer.create()
                .registerEventStorageEngine { configuration ->
                    PostgresqlEventStorageEngine(dataSource, configuration.getComponent(EventConverter::class.java), SchemaInitialization.CREATE_IF_MISSING)
                }
                .start()
    }
}
