package com.playchale.api.competitions.internal.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.playchale.api.shared.error.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Private exports. CSV cells are neutralised against spreadsheet formula injection. */
@Service
public class CorporateReportService {

	private final JdbcClient jdbc;
	private final CorporateOperationsService operations;
	private final Clock clock;

	public CorporateReportService(JdbcClient jdbc, CorporateOperationsService operations, Clock clock) {
		this.jdbc = jdbc;
		this.operations = operations;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public byte[] csv(UUID competitionId, String type, UUID actorId) {
		operations.authorizeManager(competitionId, actorId);
		var rows = switch (type) {
			case "rosters" -> query("""
					SELECT t.name,r.display_name,coalesce(r.employee_reference,''),r.eligibility_state,
					 CASE WHEN r.user_id IS NULL THEN 'No' ELSE 'Yes' END
					FROM roster_members r JOIN teams t ON t.id=r.team_id WHERE r.competition_id=:id ORDER BY t.name,r.display_name
					""", competitionId, List.of("Team", "Player", "Employee reference", "Eligibility", "Claimed"));
			case "schedule" -> query("""
					SELECT g.fixture_round,ht.name,at.name,g.starts_at,g.duration_minutes,coalesce(l.name,''),g.status,
					 coalesce(gr.home_score::text,''),coalesce(gr.away_score::text,'')
					FROM games g JOIN teams ht ON ht.id=g.home_team_id JOIN teams at ON at.id=g.away_team_id
					LEFT JOIN competition_locations l ON l.id=g.competition_location_id LEFT JOIN game_results gr ON gr.game_id=g.id
					WHERE g.competition_id=:id ORDER BY g.starts_at
					""", competitionId, List.of("Round", "Home", "Away", "Kick-off", "Minutes", "Location", "Status", "Home score", "Away score"));
			case "attendance" -> query("""
					SELECT g.starts_at,t.name,r.display_name,p.participation,p.checked_in,p.goals,p.assists,
					 coalesce(string_agg(c.colour, ', ' ORDER BY c.minute), '')
					FROM match_sheet_players p JOIN games g ON g.id=p.game_id JOIN teams t ON t.id=p.team_id
					JOIN roster_members r ON r.id=p.roster_member_id LEFT JOIN match_sheet_cards c ON c.game_id=p.game_id AND c.roster_member_id=p.roster_member_id
					WHERE g.competition_id=:id GROUP BY g.starts_at,t.name,r.display_name,p.participation,p.checked_in,p.goals,p.assists
					ORDER BY g.starts_at,t.name,r.display_name
					""", competitionId, List.of("Kick-off", "Team", "Player", "Participation", "Checked in", "Goals", "Assists", "Cards"));
			case "finance" -> query("""
					SELECT t.name,f.amount_due,f.status,coalesce(f.method,''),coalesce(f.reference,''),coalesce(f.paid_at::text,''),coalesce(f.private_note,'')
					FROM competition_entry_finance f JOIN teams t ON t.id=f.team_id WHERE f.competition_id=:id ORDER BY t.name
					""", competitionId, List.of("Team", "Amount due", "Status", "Method", "Reference", "Paid at", "Private note"));
			default -> throw BusinessException.invalid("Choose rosters, schedule, attendance or finance.");
		};
		return rows.getBytes(java.nio.charset.StandardCharsets.UTF_8);
	}

	private String query(String sql, UUID id, List<String> headings) {
		var out = new StringBuilder();
		line(out, headings);
		jdbc.sql(sql).param("id", id).query((rs, number) -> {
			var values = new ArrayList<String>();
			for (int i = 1; i <= headings.size(); i++) values.add(rs.getString(i));
			line(out, values);
			return number;
		}).list();
		return out.toString();
	}

	private static void line(StringBuilder out, List<String> values) {
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) out.append(',');
			var value = values.get(i) == null ? "" : values.get(i);
			if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
			out.append('"').append(value.replace("\"", "\"\"")).append('"');
		}
		out.append("\r\n");
	}

	@Transactional(readOnly = true)
	public byte[] pdf(UUID competitionId, UUID actorId) {
		operations.authorizeManager(competitionId, actorId);
		var summary = jdbc.sql("""
				SELECT c.name,c.status,o.name,o.primary_colour,
				 (SELECT count(*) FROM competition_entries e WHERE e.competition_id=c.id),
				 (SELECT count(*) FROM roster_members r WHERE r.competition_id=c.id AND r.eligibility_state='approved'),
				 (SELECT count(*) FROM games g WHERE g.competition_id=c.id),
				 (SELECT count(*) FROM games g JOIN game_results gr ON gr.game_id=g.id WHERE g.competition_id=c.id),
				 (SELECT coalesce(sum(f.amount_due),0) FROM competition_entry_finance f WHERE f.competition_id=c.id),
				 (SELECT coalesce(sum(f.amount_due),0) FROM competition_entry_finance f WHERE f.competition_id=c.id AND f.status='paid')
				FROM competitions c JOIN organisations o ON o.id=c.organisation_id WHERE c.id=:id
				""").param("id", competitionId).query((rs, n) -> new Summary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
				rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getLong(9), rs.getLong(10))).single();
		var table = jdbc.sql("""
				WITH matches AS (
				 SELECT g.home_team_id team,gr.home_score gf,gr.away_score ga FROM games g JOIN game_results gr ON gr.game_id=g.id WHERE g.competition_id=:id
				 UNION ALL SELECT g.away_team_id,gr.away_score,gr.home_score FROM games g JOIN game_results gr ON gr.game_id=g.id WHERE g.competition_id=:id)
				SELECT t.name,count(m.team),coalesce(sum(CASE WHEN m.gf>m.ga THEN 1 ELSE 0 END),0),
				 coalesce(sum(CASE WHEN m.gf=m.ga THEN 1 ELSE 0 END),0),coalesce(sum(m.gf-m.ga),0),
				 coalesce(sum(CASE WHEN m.gf>m.ga THEN c.points_win WHEN m.gf=m.ga THEN c.points_draw ELSE c.points_loss END),0) points
				FROM competition_entries e JOIN teams t ON t.id=e.team_id JOIN competitions c ON c.id=e.competition_id
				LEFT JOIN matches m ON m.team=t.id WHERE e.competition_id=:id GROUP BY t.id,t.name ORDER BY points DESC,5 DESC,t.name
				""").param("id", competitionId).query((rs, n) -> "%s — P %d, W %d, D %d, GD %+d, Pts %d".formatted(
				rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6))).list();
		var results = jdbc.sql("""
				SELECT ht.name,gr.home_score,gr.away_score,at.name FROM games g JOIN game_results gr ON gr.game_id=g.id
				JOIN teams ht ON ht.id=g.home_team_id JOIN teams at ON at.id=g.away_team_id WHERE g.competition_id=:id ORDER BY g.starts_at
				""").param("id", competitionId).query((rs,n) -> "%s %d-%d %s".formatted(rs.getString(1),rs.getInt(2),rs.getInt(3),rs.getString(4))).list();
		var players = jdbc.sql("""
				SELECT r.display_name,sum(p.goals),sum(p.assists),count(*) FILTER (WHERE p.checked_in)
				FROM match_sheet_players p JOIN roster_members r ON r.id=p.roster_member_id JOIN games g ON g.id=p.game_id
				WHERE g.competition_id=:id GROUP BY r.id,r.display_name ORDER BY sum(p.goals) DESC,sum(p.assists) DESC,r.display_name LIMIT 20
				""").param("id", competitionId).query((rs,n) -> "%s — goals %d, assists %d, attended %d".formatted(
				rs.getString(1),rs.getInt(2),rs.getInt(3),rs.getInt(4))).list();
		var cards = jdbc.sql("""
				SELECT r.display_name,count(*) FILTER (WHERE c.colour='yellow'),count(*) FILTER (WHERE c.colour='red')
				FROM match_sheet_cards c JOIN roster_members r ON r.id=c.roster_member_id JOIN games g ON g.id=c.game_id
				WHERE g.competition_id=:id GROUP BY r.id,r.display_name ORDER BY 2 DESC,3 DESC,r.display_name
				""").param("id", competitionId).query((rs,n) -> "%s — yellow %d, red %d".formatted(rs.getString(1),rs.getInt(2),rs.getInt(3))).list();
		try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
			var info = new PDDocumentInformation();
			info.setTitle(summary.name() + " report"); info.setAuthor("PlayChale"); info.setCreator("PlayChale Corporate Operations");
			document.setDocumentInformation(info);
			var lines = new ArrayList<String>();
			lines.add("OPERATIONAL REPORT"); lines.add(summary.name()); lines.add(summary.organisation());
			lines.add("Generated " + DateTimeFormatter.ISO_INSTANT.format(clock.instant().atOffset(ZoneOffset.UTC)));
			lines.add("Status: " + ("finished".equals(summary.status()) ? "Final" : "Interim")); lines.add("");
			lines.add("Participation"); lines.add("Teams: %d    Approved roster records: %d".formatted(summary.teams(), summary.players()));
			lines.add("Fixtures: %d    Played: %d".formatted(summary.fixtures(), summary.played())); lines.add("");
			lines.add("Standings"); lines.addAll(table); lines.add("");
			lines.add("Results"); lines.addAll(results.isEmpty() ? List.of("No official results submitted.") : results); lines.add("");
			lines.add("Attendance and scorers"); lines.addAll(players.isEmpty() ? List.of("No match sheets submitted.") : players); lines.add("");
			lines.add("Cards"); lines.addAll(cards.isEmpty() ? List.of("No cards recorded.") : cards); lines.add("");
			lines.add("Finance totals (tracked externally)"); lines.add("Due: %d    Marked paid: %d".formatted(summary.due(), summary.paid()));
			write(document, lines, Color.decode(summary.colour()));
			document.save(bytes);
			return bytes.toByteArray();
		}
		catch (IOException e) {
			throw new IllegalStateException("Could not generate report", e);
		}
	}

	private static void write(PDDocument document, List<String> lines, Color brand) throws IOException {
		var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
		var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
		PDPage page = null; PDPageContentStream stream = null; float y = 0;
		try {
			for (int i = 0; i < lines.size(); i++) {
				if (stream == null || y < 64) {
					if (stream != null) stream.close();
					page = new PDPage(PDRectangle.A4); document.addPage(page); stream = new PDPageContentStream(document, page);
					stream.setNonStrokingColor(brand); stream.addRect(0, 806, 595, 36); stream.fill(); y = 775;
				}
				var line = lines.get(i); var heading = i == 0 || line.equals("Participation") || line.equals("Standings") || line.equals("Results") || line.startsWith("Attendance") || line.equals("Cards") || line.startsWith("Finance");
				stream.beginText(); stream.setFont(heading ? bold : regular, i == 1 ? 20 : heading ? 12 : 10);
				stream.setNonStrokingColor(Color.DARK_GRAY); stream.newLineAtOffset(48, y); stream.showText(ascii(line)); stream.endText(); y -= i == 1 ? 30 : 18;
			}
			stream.beginText(); stream.setFont(regular, 8); stream.setNonStrokingColor(Color.GRAY); stream.newLineAtOffset(48, 34);
			stream.showText("Powered by PlayChale"); stream.endText();
		}
		finally { if (stream != null) stream.close(); }
	}

	private static String ascii(String value) { return value.replace('—', '-').replace('’', '\''); }
	private record Summary(String name, String status, String organisation, String colour, int teams, int players,
			int fixtures, int played, long due, long paid) {
	}
}
