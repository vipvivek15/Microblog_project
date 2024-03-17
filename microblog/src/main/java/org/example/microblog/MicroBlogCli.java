package org.example.microblog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.io.*;
import java.security.*;

@SpringBootApplication
@Command(name = "microblog", mixinStandardHelpOptions = true, version = "microblog 1.0",
		description = "Interacts with the MicroBlog service.",
		subcommands = {MicroBlogCli.PostCommand.class, MicroBlogCli.ListCommand.class, MicroBlogCli.CreateCommand.class})
public class MicroBlogCli implements Runnable{

	public static void main(String[] args) {
		int exitCode = new CommandLine(new MicroBlogCli()).execute(args);
		System.exit(exitCode);
	}

	@Override
	public void run() {
		// This will be invoked if no subcommand is specified.
		System.out.println("Welcome to the MicroBlog CLI. Use -h for help.");
	}

	// PostCommand, ListCommand, and CreateCommand classes go here
	@Command(name = "list", description = "Lists messages from the MicroBlog.")
	protected static class ListCommand implements Runnable {

		@Option(names = {"--starting", "-s"}, description = "ID to start listing from.")
		private Integer startingId;

		@Option(names = {"--count", "-c"}, description = "Number of messages to retrieve.", defaultValue = "10")
		private Integer count;

		@Option(names = {"--save-attachment", "-sa"}, description = "Save attachments if present.")
		private boolean saveAttachment;

		private static final String FILE_NAME = "C:\\Users\\vipvi\\OneDrive\\Desktop\\CMPE 272\\Microblog_project\\microblog\\private\\mb.ini";

		private final OkHttpClient client = new OkHttpClient();
		private final ObjectMapper mapper = new ObjectMapper();

		@Override
		public void run() {
			try {
				if (startingId == null || startingId > fetchLatestMessageId()) {
					startingId = fetchLatestMessageId(); // Fetch the latest message ID
				}
				// Default count if not provided
				if (count == null || count > 20) {
					count = 10;
				}
				// Handle pagination if count > 20
				int remainingMessages = count;
				Integer next = startingId;

				while (remainingMessages > 0) {
					int requestCount = Math.min(remainingMessages, 20);
					List<Map<String, Object>> messages = fetchMessages(requestCount, next);

					// Process each message
					for (Map<String, Object> message : messages) {
						if (remainingMessages > 0) {
							printMessage(message);
							if (saveAttachment) {
								saveAttachment(message);
							}
							next = (Integer) message.get("message-id") - 1;
							remainingMessages--;
						} else {
							break; // Exit if the requested count of messages is reached
						}
					}

					if (messages.size() < requestCount) {
						break; // No more messages to fetch
					}
				}
			} catch (IOException e) {
				System.err.println("An error occurred while retrieving messages: " + e.getMessage());
			} catch (Exception e) {
				System.err.println("An unexpected error occurred: " + e.getMessage());
			}
		}

		private List<Map<String, Object>> fetchMessages(int count, Integer next) throws IOException {
			HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse("http://127.0.0.1:8080/messages")).newBuilder();
			urlBuilder.addQueryParameter("limit", String.valueOf(count));
			if (next != null) {
				urlBuilder.addQueryParameter("next", String.valueOf(next));
			}
			String url = urlBuilder.build().toString();

			Request request = new Request.Builder()
					.url(url)
					.build();

			try (Response response = client.newCall(request).execute()) {
				assert response.body() != null;
				String responseBody = response.body().string();
				List<Map<String, Object>> messages = mapper.readValue(responseBody, new TypeReference<>() {});

				// Verify message signatures
				for (Map<String, Object> message : messages) {
					if (!verifyMessageSignature(message)) {
						throw new IOException("Message signature verification failed.");
					}
				}

				return messages;
			}
		}

		private Integer fetchLatestMessageId() throws IOException {
			HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse("http://127.0.0.1:8080/messages")).newBuilder();
			urlBuilder.addQueryParameter("count", "1");
			String url = urlBuilder.build().toString();

			Request request = new Request.Builder()
					.url(url)
					.build();

			try (Response response = client.newCall(request).execute()) {
				if (response.isSuccessful() && response.body() != null) {
					String responseBody = response.body().string();
					List<Map<String, Object>> messages = mapper.readValue(responseBody, new TypeReference<>() {});

					// Sort messages by ID in descending order
					sortMessagesByMessageId(messages);

					if (!messages.isEmpty() && verifyMessageSignature(messages.get(0))) {
						return (Integer) messages.get(0).get("message-id");
					} else if (messages.isEmpty()) {
						throw new IOException("No messages returned from the server");
					} else {
						throw new IOException("Message signature verification failed");
					}
				} else {
					throw new IOException("Failed to fetch messages: " + response.message());
				}
			}
		}

		private void sortMessagesByMessageId(List<Map<String, Object>> messages) {
			messages.sort(Comparator.comparingInt(this::extractMessageId).reversed());
		}

		private int extractMessageId(Object message) {
			if (message instanceof Map<?, ?> rawMap) {
                Object messageIdObj = rawMap.get("message-id");
				if (messageIdObj instanceof Integer) {
					return (Integer) messageIdObj;
				}
			}
			return Integer.MIN_VALUE;
		}

		private boolean verifyMessageSignature(Map<String, Object> message) throws IOException {
			try {
				// Load public key from mb.ini
				PublicKey publicKey = loadPublicKey();


				// Construct the message JSON
				Map<String, Object> verificationMap = new LinkedHashMap<>(message);
				verificationMap.remove("message-id");
				verificationMap.remove("signature");
				String receivedSignature = (String) message.get("signature");
				if (receivedSignature == null) {
					System.out.println("Signature not found in the data");
					return false;
				}

				// Serialize the json
				String serializedData = constructMessageJson(verificationMap);

				Signature signature = Signature.getInstance("SHA256withRSA");
				signature.initVerify(publicKey);
				signature.update(serializedData.getBytes(StandardCharsets.UTF_8));

				byte[] decodedSignature = Base64.getDecoder().decode(receivedSignature);

				return signature.verify(decodedSignature);

			} catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
				System.err.println("Error of type: " + e.getCause());
				throw new IOException("Error verifying message signature: " + e.getMessage());
			}
		}

		private String constructMessageJson(Map<String, Object> message)  {
			// Constructing the JSON string without formatting whitespace
			ObjectMapper mapper = new ObjectMapper();
			try {
				return mapper.writeValueAsString(message);
			} catch (JsonProcessingException e) {
				// Log the error, return a default value, or throw a custom exception
				System.err.println("Error serializing map to JSON: " + e.getMessage());
				return "{}"; // return an empty JSON object as a default/fallback
				// or throw a runtime exception
				// throw new RuntimeException("Error serializing map to JSON", e);
			}
		}

		private PublicKey loadPublicKey() throws IOException {
			try (FileInputStream fis = new FileInputStream(FILE_NAME);
				 ObjectInputStream ois = new ObjectInputStream(fis)) {
				String username = (String) ois.readObject();
				return (PublicKey) ois.readObject();
			} catch (ClassNotFoundException e) {
				throw new IOException("Error loading public key: " + e.getMessage());
			}
		}

		private void printMessage(Map<String, Object> message) {
			String output = String.format("\"message-id\": %s, \"date\": \"%s\", \"author\": \"%s\", \"message\": \"%s\"",
					message.get("message-id"),
					message.get("date"),
					message.get("author"),
					message.get("message"));

			// If there's an attachment, append the attachment emoji and the key-value pair in quotes.
			if (message.containsKey("attachment") && !((String) message.get("attachment")).isEmpty()) {
				String attachmentContent = (String)message.get("attachment");

				// Appending the paperclip emoji, a space, and then the attachment content
				output += String.format(", \"attachment\": \"📎 %s\"", attachmentContent);
			}

			// Append the signature in quotes to the output.
			output += String.format(", \"signature\": \"%s\"", message.get("signature"));

			System.out.println("{" + output + "}");
		}

		private void saveAttachment(Map<String, Object> message) {
			if (message.containsKey("attachment") && !((String) message.get("attachment")).isEmpty()) {
				String attachment = (String) message.get("attachment");
				byte[] decodedBytes = Base64.getDecoder().decode(attachment);
				String fileName = message.get("message-id") + ".out";
				Path directoryPath = Paths.get("C:\\Users\\vipvi\\OneDrive\\Desktop\\CMPE 272\\Microblog_project\\microblog\\private");
				Path filePath = directoryPath.resolve(fileName);

				try {
					Scanner scanner = new Scanner(System.in);
					if (Files.exists(filePath)) {
						String input;
						do {
							System.out.println("File " + fileName + " already exists. Do you want to overwrite it? (yes/no): ");
							input = scanner.nextLine().trim().toLowerCase();
						} while (!input.equals("yes") && !input.equals("no"));

						if (input.equals("no")) {
							System.out.println("File not overwritten. Operation cancelled by the user.");
							return; // Exit the method if the user does not want to overwrite
						}
					}

					System.out.println("Saving file: " + fileName + " to the following path: " + filePath);
					Files.write(filePath, decodedBytes); // This will overwrite the file if it exists
				} catch (IOException e) {
					System.err.println("Error saving attachment: " + e.getMessage());
				}
			}
		}
	}

	@Command(name = "post", description = "Posts a new message to the MicroBlog.")
	protected static class PostCommand implements Runnable {

		@Parameters(index = "0", description = "The message to post.")
		private static String message;

		@Option(names = {"-f", "--file"}, description = "File to attach.")
		private static File fileToAttach;

		private static final String FILE_NAME = "C:\\Users\\vipvi\\OneDrive\\Desktop\\CMPE 272\\Microblog_project\\microblog\\private\\mb.ini";

		private final OkHttpClient client = new OkHttpClient();

        @Override
		public void run() {
			try {
				String username = loadUserData();
				// Validate the message
				if (message == null || message.trim().isEmpty()) {
					System.err.println("Message cannot be empty.");
					return;
				}

				// Load keys from mb.ini file
				KeyPair keyPair = loadKeys();

				// Validate and process the file attachment
				String attachmentEncoded = null;
				if (fileToAttach != null) {
					if (!fileToAttach.exists() || fileToAttach.isDirectory()) {
						System.err.println("The specified file does not exist or is a directory.");
						return;
					}

					// Check file size (example: limit to 10MB)
					if (fileToAttach.length() > 10 * 1024 * 1024) {
						System.err.println("File is too large. Maximum allowed size is 10MB.");
						return;
					}

					byte[] fileContent = Files.readAllBytes(fileToAttach.toPath());
					attachmentEncoded = Base64.getEncoder().encodeToString(fileContent);
				}

				// Prepare the request payload
				Map<String, String> contentMap = new LinkedHashMap<>(); // Using LinkedHashMap to maintain insertion order
				DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
				String formattedDate = ZonedDateTime.now().format(formatter);

				contentMap.put("date", formattedDate);
				contentMap.put("author", username); // Replace this with actual user identification logic
				contentMap.put("message", message);
				if (attachmentEncoded != null) {
					contentMap.put("attachment", attachmentEncoded);
				}



				// Serialize the content map to JSON for signing (without the signature)
				String jsonToSign = serializeMapToJson(contentMap);

				// Sign the JSON string
				String signedMessage = signMessage(jsonToSign, keyPair.getPrivate());

				// Add the signature to the original map (not the string)
				contentMap.put("signature", signedMessage);

				// Now, serialize the updated content map which includes the signature
				String jsonToSend = serializeMapToJson(contentMap);

				// Create the request body
				RequestBody body = RequestBody.create(jsonToSend, MediaType.get("application/json; charset=utf-8"));

				// Create the request
                // Could be externalized to a configuration file or environment variable
                String serverUrl = "http://127.0.0.1:8080/messages";
                Request request = new Request.Builder()
						.url(new URL(serverUrl))
						.post(body)
						.build();

				// Execute the request and handle the response
				try (Response response = client.newCall(request).execute()) {
					if (!response.isSuccessful()) {
						System.err.println("Failed to post message. Server returned error: " + response.code());
						return;
					}
                    assert response.body() != null;
					String responseBody = response.body().string();
					ObjectMapper mapper = new ObjectMapper();
					Map<String, Object> responseMap = mapper.readValue(responseBody, new TypeReference<>() {
                    });
					System.out.println("{\"message-id\": " + responseMap.get("message-id") + "}");
				}
			} catch (IOException e) {
				System.err.println("An error occurred while trying to post the message: " + e.getMessage());
			} catch (Exception e) {
				System.err.println("An unexpected error occurred: " + e.getMessage());
			}
		}

		private String loadUserData() throws IOException, ClassNotFoundException {
			try (FileInputStream fis = new FileInputStream(FILE_NAME);
				 ObjectInputStream ois = new ObjectInputStream(fis)) {
				return (String) ois.readObject();
			}
		}

		private String signMessage(String message, PrivateKey privateKey) throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(privateKey);
			signature.update(message.getBytes());
			byte[] signedBytes = signature.sign();
			return Base64.getEncoder().encodeToString(signedBytes);
		}

		private KeyPair loadKeys() throws IOException, ClassNotFoundException {
			try (FileInputStream fis = new FileInputStream(FILE_NAME);
				 ObjectInputStream ois = new ObjectInputStream(fis)) {

				String username = (String) ois.readObject();
				PublicKey publicKey = (PublicKey) ois.readObject();
				PrivateKey privateKey = (PrivateKey) ois.readObject();
				return new KeyPair(publicKey, privateKey);
			}
		}

		private String serializeMapToJson(Map<String, String> map)  {
			ObjectMapper mapper = new ObjectMapper();
			try {
				return mapper.writeValueAsString(map);
			} catch (JsonProcessingException e) {
				// Log the error, return a default value, or throw a custom exception
				System.err.println("Error serializing map to JSON: " + e.getMessage());
				return "{}"; // return an empty JSON object as a default/fallback
				// or throw a runtime exception
				// throw new RuntimeException("Error serializing map to JSON", e);
			}
		}
	}

	@Command(name = "create", description = "Generates a new ID and saves it to mb.ini.")
	protected static class CreateCommand implements Runnable {
		private static final String FILE_NAME = "C:\\Users\\vipvi\\OneDrive\\Desktop\\CMPE 272\\Microblog_project\\microblog\\private\\mb.ini";

		@Override
		public void run() {
			try {
				File file = new File(FILE_NAME);

				if (file.exists()) {
					System.out.println(FILE_NAME + " already exists. Overwrite? (y/n): ");
					BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
					String input = reader.readLine();
					if (!"y".equalsIgnoreCase(input.trim())) {
						System.out.println("Operation cancelled by the user.");
						return;
					}
				}

				// Generate the RSA key pair
				KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
				keyGen.initialize(2048);
				KeyPair pair = keyGen.generateKeyPair();

				// Prompt for username
				System.out.println("Enter a new username: ");
				BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
				String username = reader.readLine().trim();

				// Save the keys and username securely
				saveUserData(username, pair);

				System.out.println("Public and private keys along with username have been generated and saved to " + FILE_NAME);
				System.out.println("{ message: \"welcome\" }");
			} catch (Exception e) {
				System.err.println("An error occurred: " + e.getMessage());
			}
		}

		private void saveUserData(String username, KeyPair keyPair) throws IOException {
			try (FileOutputStream fos = new FileOutputStream(FILE_NAME);
				 ObjectOutputStream oos = new ObjectOutputStream(fos)) {
				oos.writeObject(username);
				oos.writeObject(keyPair.getPublic());
				oos.writeObject(keyPair.getPrivate());
			}
		}
	}
}