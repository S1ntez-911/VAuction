package com.valorcraft.vauction.persistence;

import com.valorcraft.vauction.domain.sale.AuctionSale;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий завершённых продаж. Уникальны listing_id (одна продажа на лот),
 * purchase_operation_id и escrow_reference: повторная запись невозможна.
 */
public final class SaleRepository {

    private static final String COLUMNS = "sale_id, listing_id, purchase_operation_id, seller_uuid, buyer_uuid, "
            + "gross_minor, commission_minor, seller_net_minor, escrow_reference, item_hash, sold_at";

    public long insert(Connection c, AuctionSale sale) {
        String sql = "INSERT INTO auction_sales (listing_id, purchase_operation_id, seller_uuid, buyer_uuid, "
                + "gross_minor, commission_minor, seller_net_minor, escrow_reference, item_hash, sold_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sale.listingId());
            ps.setString(2, sale.purchaseOperationId());
            ps.setString(3, sale.sellerUuid().toString());
            ps.setString(4, sale.buyerUuid().toString());
            ps.setLong(5, sale.grossMinor());
            ps.setLong(6, sale.commissionMinor());
            ps.setLong(7, sale.sellerNetMinor());
            ps.setString(8, sale.escrowReference());
            ps.setString(9, sale.itemHash());
            ps.setLong(10, sale.soldAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new DatabaseException("sale id not returned");
                }
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                throw new DatabaseException("sale already exists (listing_id/operation/escrow conflict)", e);
            }
            throw new DatabaseException("insert sale failed", e);
        }
    }

    public Optional<AuctionSale> findById(Connection c, long saleId) {
        String sql = "SELECT " + COLUMNS + " FROM auction_sales WHERE sale_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("find sale failed: " + saleId, e);
        }
    }

    public Optional<AuctionSale> findByListingId(Connection c, long listingId) {
        String sql = "SELECT " + COLUMNS + " FROM auction_sales WHERE listing_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("find sale by listing failed: " + listingId, e);
        }
    }

    private static AuctionSale map(ResultSet rs) throws SQLException {
        return new AuctionSale(
                rs.getLong("sale_id"),
                rs.getLong("listing_id"),
                rs.getString("purchase_operation_id"),
                UUID.fromString(rs.getString("seller_uuid")),
                UUID.fromString(rs.getString("buyer_uuid")),
                rs.getLong("gross_minor"),
                rs.getLong("commission_minor"),
                rs.getLong("seller_net_minor"),
                rs.getString("escrow_reference"),
                rs.getString("item_hash"),
                rs.getLong("sold_at"));
    }
}