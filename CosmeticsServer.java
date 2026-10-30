import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Ether Visuals cosmetics server: remembers what every online client user wears and tells clients what the
 * players around them wear. No dependencies; run with {@code java CosmeticsServer.java} (Java 21).
 *
 * <p>Settings come from environment variables:
 * <ul>
 *   <li>{@code PORT} - port to listen on, 8085 by default;</li>
 *   <li>{@code ALLOW_OFFLINE} - {@code true} (default) lets accounts that cannot prove themselves to Mojang
 *   in, but only under their offline-mode UUID, so they cannot pose as a licensed player.</li>
 * </ul>
 *
 * <p>Endpoints, all JSON:
 * <pre>
 * POST /auth/challenge {name, uuid}            -> {serverId}
 * POST /auth/verify    {name, uuid, serverId}  -> {token}       (after the client joined serverId at Mojang)
 * PUT  /cosmetics      {wings, hat, ...}       -> 204           (Authorization: Bearer token; also a heartbeat)
 * POST /cosmetics/lookup {uuids: [...]}        -> {uuid: {...}} (only users seen in the last 10 minutes)
 * </pre>
 * Nothing is written to disk: a user who stops sending heartbeats simply drops out.
 */
public final class CosmeticsServer {
	private static final Set<String> SLOTS = Set.of("wings", "hat", "pet", "backpack", "cape");
	private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_-]{1,48}");
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final long ONLINE_MS = 10 * 60_000;
	private static final long TOKEN_MS = 24 * 60 * 60_000L;
	private static final long CHALLENGE_MS = 2 * 60_000;
	private static final int MAX_BODY = 16 * 1024;
	private static final int MAX_LOOKUP = 200;
	private static final int REQUESTS_PER_WINDOW = 60;
	private static final long WINDOW_MS = 10_000;

	private record Challenge(String name, UUID uuid, long expires) {
	}

	private record Session(UUID uuid, long expires) {
	}

	private record Wearing(Map<String, String> items, long seen) {
	}

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final HttpClient MOJANG = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final Map<String, Challenge> CHALLENGES = new ConcurrentHashMap<>();
	private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
	private static final Map<UUID, Wearing> WEARING = new ConcurrentHashMap<>();
	private static final Map<String, long[]> RATE = new ConcurrentHashMap<>();

	private static boolean allowOffline;

	public static void main(String[] args) throws IOException {
		int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8085"));
		allowOffline = Boolean.parseBoolean(System.getenv().getOrDefault("ALLOW_OFFLINE", "true"));
		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/", CosmeticsServer::handle);
		server.setExecutor(Executors.newFixedThreadPool(8));
		server.start();
		Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(CosmeticsServer::cleanUp, 1, 1, java.util.concurrent.TimeUnit.MINUTES);
		System.out.println("Cosmetics server on port " + port + (allowOffline ? " (offline accounts allowed)" : ""));
	}

	private static void handle(HttpExchange exchange) throws IOException {
		try {
			if (!allow(exchange.getRemoteAddress().getAddress().getHostAddress())) {
				reply(exchange, 429, null);
				return;
			}
			String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
			switch (route) {
				case "POST /auth/challenge" -> challenge(exchange);
				case "POST /auth/verify" -> verify(exchange);
				case "PUT /cosmetics" -> wear(exchange);
				case "POST /cosmetics/lookup" -> lookup(exchange);
				default -> reply(exchange, 404, null);
			}
		} catch (BadRequest exception) {
			reply(exchange, 400, null);
		} catch (Exception exception) {
			System.err.println("Request failed: " + exception);
			reply(exchange, 500, null);
		} finally {
			exchange.close();
		}
	}

	private static void challenge(HttpExchange exchange) throws IOException {
		Map<String, Object> body = body(exchange);
		String name = string(body, "name");
		UUID uuid = uuid(string(body, "uuid"));
		if (!NAME.matcher(name).matches()) throw new BadRequest();
		String serverId = HexFormat.of().formatHex(bytes(16));
		CHALLENGES.put(serverId, new Challenge(name, uuid, System.currentTimeMillis() + CHALLENGE_MS));
		reply(exchange, 200, Map.of("serverId", serverId));
	}

	private static void verify(HttpExchange exchange) throws Exception {
		Map<String, Object> body = body(exchange);
		Challenge challenge = CHALLENGES.remove(string(body, "serverId"));
		if (challenge == null || challenge.expires() < System.currentTimeMillis() || !challenge.name().equals(string(body, "name"))) {
			reply(exchange, 401, null);
			return;
		}
		UUID proven = mojangUuid(challenge.name(), string(body, "serverId"));
		if (proven == null && allowOffline) {
			UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + challenge.name()).getBytes(StandardCharsets.UTF_8));
			if (offline.equals(challenge.uuid())) proven = offline;
		}
		if (proven == null) {
			reply(exchange, 401, null);
			return;
		}
		String token = HexFormat.of().formatHex(bytes(32));
		SESSIONS.put(token, new Session(proven, System.currentTimeMillis() + TOKEN_MS));
		reply(exchange, 200, Map.of("token", token, "uuid", proven.toString()));
	}

	/** Asks Mojang whether this account joined the given server id; its UUID when it did. */
	private static UUID mojangUuid(String name, String serverId) throws Exception {
		URI uri = URI.create("https://sessionserver.mojang.com/session/minecraft/hasJoined?username="
			+ URLEncoder.encode(name, StandardCharsets.UTF_8) + "&serverId=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8));
		HttpResponse<String> response = MOJANG.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) return null;
		Object parsed = Json.parse(response.body());
		if (!(parsed instanceof Map<?, ?> map) || !(map.get("id") instanceof String id) || id.length() != 32) return null;
		return UUID.fromString(id.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
	}

	private static void wear(HttpExchange exchange) throws IOException {
		Session session = session(exchange);
		if (session == null) {
			reply(exchange, 401, null);
			return;
		}
		Map<String, String> items = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : body(exchange).entrySet()) {
			if (SLOTS.contains(entry.getKey()) && entry.getValue() instanceof String id && ITEM_ID.matcher(id).matches()) {
				items.put(entry.getKey(), id);
			}
		}
		WEARING.put(session.uuid(), new Wearing(items, System.currentTimeMillis()));
		reply(exchange, 204, null);
	}

	private static void lookup(HttpExchange exchange) throws IOException {
		if (session(exchange) == null) {
			reply(exchange, 401, null);
			return;
		}
		if (!(body(exchange).get("uuids") instanceof List<?> uuids) || uuids.size() > MAX_LOOKUP) throw new BadRequest();
		long now = System.currentTimeMillis();
		Map<String, Object> out = new LinkedHashMap<>();
		for (Object raw : uuids) {
			if (!(raw instanceof String text)) continue;
			Wearing wearing = WEARING.get(uuid(text));
			if (wearing != null && now - wearing.seen() < ONLINE_MS && !wearing.items().isEmpty()) out.put(text, wearing.items());
		}
		reply(exchange, 200, out);
	}

	private static Session session(HttpExchange exchange) {
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		if (header == null || !header.startsWith("Bearer ")) return null;
		Session session = SESSIONS.get(header.substring(7).trim());
		return session != null && session.expires() > System.currentTimeMillis() ? session : null;
	}

	private static boolean allow(String address) {
		long now = System.currentTimeMillis();
		long[] window = RATE.computeIfAbsent(address, ignored -> new long[]{now, 0});
		synchronized (window) {
			if (now - window[0] > WINDOW_MS) {
				window[0] = now;
				window[1] = 0;
			}
			return ++window[1] <= REQUESTS_PER_WINDOW;
		}
	}

	private static void cleanUp() {
		long now = System.currentTimeMillis();
		CHALLENGES.values().removeIf(challenge -> challenge.expires() < now);
		SESSIONS.values().removeIf(session -> session.expires() < now);
		WEARING.values().removeIf(wearing -> now - wearing.seen() > ONLINE_MS);
		RATE.values().removeIf(window -> now - window[0] > WINDOW_MS);
	}

	// ---------------------------------------------------------------- plumbing

	private static final class BadRequest extends RuntimeException {
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> body(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			byte[] data = in.readNBytes(MAX_BODY + 1);
			if (data.length > MAX_BODY) throw new BadRequest();
			Object parsed = Json.parse(new String(data, StandardCharsets.UTF_8));
			if (!(parsed instanceof Map<?, ?>)) throw new BadRequest();
			return (Map<String, Object>) parsed;
		} catch (IllegalArgumentException exception) {
			throw new BadRequest();
		}
	}

	private static String string(Map<String, Object> body, String key) {
		if (body.get(key) instanceof String value) return value;
		throw new BadRequest();
	}

	private static UUID uuid(String text) {
		try {
			return UUID.fromString(text);
		} catch (IllegalArgumentException exception) {
			throw new BadRequest();
		}
	}

	private static byte[] bytes(int count) {
		byte[] out = new byte[count];
		RANDOM.nextBytes(out);
		return out;
	}

	private static void reply(HttpExchange exchange, int status, Object json) throws IOException {
		if (json == null) {
			exchange.sendResponseHeaders(status, -1);
			return;
		}
		byte[] data = Json.write(json).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, data.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(data);
		}
	}

	/** Just enough JSON: objects, arrays, strings, numbers, booleans and null. */
	private static final class Json {
		private final String text;
		private int pos;

		private Json(String text) {
			this.text = text;
		}

		static Object parse(String text) {
			Json json = new Json(text);
			Object value = json.value(0);
			json.skipSpace();
			if (json.pos != text.length()) throw new IllegalArgumentException("Trailing data");
			return value;
		}

		private Object value(int depth) {
			if (depth > 16) throw new IllegalArgumentException("Too deep");
			skipSpace();
			if (pos >= text.length()) throw new IllegalArgumentException("Unexpected end");
			char c = text.charAt(pos);
			if (c == '{') return object(depth);
			if (c == '[') return array(depth);
			if (c == '"') return string();
			if (text.startsWith("true", pos)) {
				pos += 4;
				return Boolean.TRUE;
			}
			if (text.startsWith("false", pos)) {
				pos += 5;
				return Boolean.FALSE;
			}
			if (text.startsWith("null", pos)) {
				pos += 4;
				return null;
			}
			int start = pos;
			while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
			if (start == pos) throw new IllegalArgumentException("Unexpected character");
			return Double.parseDouble(text.substring(start, pos));
		}

		private Map<String, Object> object(int depth) {
			Map<String, Object> out = new LinkedHashMap<>();
			pos++;
			skipSpace();
			if (peek('}')) return out;
			while (true) {
				skipSpace();
				if (pos >= text.length() || text.charAt(pos) != '"') throw new IllegalArgumentException("Expected key");
				String key = string();
				skipSpace();
				expect(':');
				out.put(key, value(depth + 1));
				skipSpace();
				if (peek('}')) return out;
				expect(',');
			}
		}

		private List<Object> array(int depth) {
			List<Object> out = new ArrayList<>();
			pos++;
			skipSpace();
			if (peek(']')) return out;
			while (true) {
				out.add(value(depth + 1));
				skipSpace();
				if (peek(']')) return out;
				expect(',');
			}
		}

		private String string() {
			StringBuilder out = new StringBuilder();
			pos++;
			while (pos < text.length()) {
				char c = text.charAt(pos++);
				if (c == '"') return out.toString();
				if (c != '\\') {
					out.append(c);
					continue;
				}
				if (pos >= text.length()) break;
				char e = text.charAt(pos++);
				switch (e) {
					case 'n' -> out.append('\n');
					case 't' -> out.append('\t');
					case 'r' -> out.append('\r');
					case 'b' -> out.append('\b');
					case 'f' -> out.append('\f');
					case 'u' -> {
						if (pos + 4 > text.length()) throw new IllegalArgumentException("Bad escape");
						out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
						pos += 4;
					}
					default -> out.append(e);
				}
			}
			throw new IllegalArgumentException("Unterminated string");
		}

		private boolean peek(char c) {
			if (pos < text.length() && text.charAt(pos) == c) {
				pos++;
				return true;
			}
			return false;
		}

		private void expect(char c) {
			if (!peek(c)) throw new IllegalArgumentException("Expected " + c);
		}

		private void skipSpace() {
			while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
		}

		static String write(Object value) {
			StringBuilder out = new StringBuilder();
			write(value, out);
			return out.toString();
		}

		private static void write(Object value, StringBuilder out) {
			if (value instanceof Map<?, ?> map) {
				out.append('{');
				boolean first = true;
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					if (!first) out.append(',');
					first = false;
					writeString(String.valueOf(entry.getKey()), out);
					out.append(':');
					write(entry.getValue(), out);
				}
				out.append('}');
			} else if (value instanceof List<?> list) {
				out.append('[');
				for (int i = 0; i < list.size(); i++) {
					if (i > 0) out.append(',');
					write(list.get(i), out);
				}
				out.append(']');
			} else if (value instanceof String string) {
				writeString(string, out);
			} else {
				out.append(value);
			}
		}

		private static void writeString(String string, StringBuilder out) {
			out.append('"');
			for (int i = 0; i < string.length(); i++) {
				char c = string.charAt(i);
				if (c == '"' || c == '\\') out.append('\\').append(c);
				else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
				else out.append(c);
			}
			out.append('"');
		}
	}
}
