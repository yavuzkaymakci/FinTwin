package com.fintwin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The ML roles created by V8: read-only, and only the labels role can see ground_truth. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MlDatabaseAccessTests {

    private static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(TestcontainersConfiguration.POSTGRES.getJdbcUrl(), user, password);
    }

    private static void query(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void mlRoleReadsTwinAndCoreBankingButNotGroundTruth() throws SQLException {
        try (Connection c = connect("fintwin_ml", "fintwin_ml")) {
            query(c, "SELECT COUNT(*) FROM twin.customer_twin_snapshot");
            query(c, "SELECT COUNT(*) FROM twin.customer_risk_score");
            query(c, "SELECT COUNT(*) FROM customer_event");
            query(c, "SELECT raw_description FROM bank_transaction LIMIT 1");
            assertThatThrownBy(() -> query(c, "SELECT COUNT(*) FROM ground_truth.customer_monthly_state"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> query(c, "SELECT COUNT(*) FROM ground_truth.transaction_label"))
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void mlRolesAreReadOnly() throws SQLException {
        try (Connection c = connect("fintwin_ml", "fintwin_ml")) {
            assertThatThrownBy(() -> query(c, "DELETE FROM twin.customer_risk_score"))
                    .hasMessageContaining("read-only");
        }
        try (Connection c = connect("fintwin_ml_labels", "fintwin_ml_labels")) {
            query(c, "SELECT COUNT(*) FROM ground_truth.transaction_label");
            assertThatThrownBy(() -> query(c, "DELETE FROM ground_truth.transaction_label"))
                    .hasMessageContaining("read-only");
            assertThatThrownBy(() -> query(c, "SELECT COUNT(*) FROM twin.customer_twin_state"))
                    .hasMessageContaining("permission denied");
        }
    }
}
