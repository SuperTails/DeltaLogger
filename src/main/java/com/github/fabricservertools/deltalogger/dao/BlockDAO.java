package com.github.fabricservertools.deltalogger.dao;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.github.fabricservertools.deltalogger.QueueOperation;
import com.github.fabricservertools.deltalogger.SQLUtils;
import com.github.fabricservertools.deltalogger.beans.Placement;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class BlockDAO {
  private Jdbi jdbi;
  private final String SELECT_PLACEMENT;

  public BlockDAO(Jdbi jdbi) {
    this.jdbi = jdbi;
    jdbi.registerRowMapper(Placement.class, (rs, ctx) -> {
      return new Placement(
        rs.getInt("id"),
        rs.getString("player_name"),
        rs.getString("date"),
        rs.getString("block_type"),
        rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
        rs.getBoolean("placed"),
        rs.getString("dimension")
      );
    });

    SELECT_PLACEMENT = String.join(" ",
      "SELECT PL.id, P.name AS `player_name`,", SQLUtils.getDateFormatted("date"), ", IT.name AS `block_type`, x, y, z, placed, DT.name as `dimension`",
      "FROM placements as PL",
      "INNER JOIN players as P ON P.id=player_id",
      "INNER JOIN registry as IT ON IT.id=type",
      "INNER JOIN registry as DT ON DT.id=dimension_id"
    );
  }

  /**
   * Get latest placements
   * @param idOffset must be the id of the row to offset from, if offset is 0 then get latest
   * @param limit the number of rows to return
   * @return
   */
  public List<Placement> getLatestPlacements(int idOffset, int limit) {
    return jdbi.withHandle(handle -> handle
      .select(String.join(" ",
        "SELECT PL.id, P.name AS `player_name`,",
          SQLUtils.getDateFormatted("date"),
          ", IT.name AS `block_type`, x, y, z, placed, DT.name as `dimension`",
        "FROM (SELECT * FROM placements WHERE id <",
          SQLUtils.offsetOrZeroLatest("placements", idOffset),
          "ORDER BY `id` DESC LIMIT ?) as PL",
        "INNER JOIN players as P ON P.id=player_id",
        "INNER JOIN registry as IT ON IT.id=type",
        "INNER JOIN registry as DT ON DT.id=dimension_id"
      ), limit)
      .mapTo(Placement.class)
      .list()
    );
  }

  public List<Placement> getPlacementsAt(Identifier dimension, BlockPos pos, int limit) {
    try {
      return jdbi.withHandle(handle -> handle
        .select(
          String.join(" ",
            SELECT_PLACEMENT,
            "WHERE x = ? AND y = ? AND z = ? AND DT.name = ?",
            "ORDER BY date DESC LIMIT ?"
          ),
          pos.getX(), pos.getY(), pos.getZ(),
          dimension.toString(), limit
        )
        .mapTo(Placement.class).list()
      );
    } catch (Exception e) {
      e.printStackTrace();
    }
    return new ArrayList<>();
  }

  public static QueueOperation insertPlacement(
    UUID player_id,
    Identifier blockid,
    boolean placed,
    BlockPos pos,
    Identifier dimension_id,
    Instant date
  ) {
    return new QueueOperation() {
      public int getPriority() { return 2; }
  
      public PreparedBatch prepareBatch(Handle handle) {
        return handle.prepareBatch(String.join(" ",
          "INSERT INTO placements (date, placed, x, y, z, player_id, type, dimension_id)",
          "SELECT :date, :placed, :x, :y, :z,",
            "(SELECT id FROM players WHERE uuid=:playeruuid),",
            "(SELECT id FROM registry WHERE name=:blockid),",
            "(SELECT id FROM registry WHERE name=:dimension_id)"
        ));
      }
  
      public PreparedBatch addBindings(PreparedBatch batch) {
        return batch
          .bind("date", SQLUtils.instantToUTCString(date))
          .bind("placed", placed)
          .bind("x", pos.getX()).bind("y", pos.getY()).bind("z", pos.getZ())
          .bind("playeruuid", player_id.toString())
          .bind("blockid", blockid.toString())
          .bind("dimension_id", dimension_id.toString())
          .add();
      }
    };
  }
}
