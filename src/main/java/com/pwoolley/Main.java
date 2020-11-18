package com.pwoolley;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * Utility to delete contexts from a kubernetes config.
 * <p>
 * It basically reads the config, asks for a yes/no to delete each context (and associated cluster
 * and user) and then writes the modified context back.
 */
public class Main {

    /**
     * Helper class to hold together the index of a context and the name
     * of its associated cluster and user.
     */
    private static final class ContextInfo {

        /**
         * This index of the context in the config.
         */
        private final int contextIndex;

        /**
         * The name of the cluster associated with the context.
         */
        private final String clusterName;

        /**
         * The name of the user associated with the context.
         */
        private final String userName;

        /**
         * Constructor.
         *
         * @param contextIndex the index of the context within the config.
         * @param clusterName the name of the cluster associated with the context.
         * @param userName the name of the user associated with the context.
         */
        private ContextInfo(int contextIndex, String clusterName, String userName) {
            this.contextIndex = contextIndex;
            this.clusterName = clusterName;
            this.userName = userName;
        }

        /**
         * Create a new instance.
         *
         * @param contextIndex the index of the context within the config.
         * @param clusterName the name of the cluster associated with the context.
         * @param userName the name of the user associated with the context.
         * @return a new {@link ContextInfo} instance.
         */
        private static ContextInfo of(int contextIndex, String clusterName, String userName) {
            return new ContextInfo(contextIndex, clusterName, userName);
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", ContextInfo.class.getSimpleName() + "[", "]")
                    .add("contextIndex=" + contextIndex)
                    .add("clusterName='" + clusterName + "'")
                    .add("userName='" + userName + "'")
                    .toString();
        }
    }

    /**
     * Default config filename to read and write.
     */
    private static final String DEFAULT_CONFIG_FILENAME = System.getProperty("user.home") + "/.kube/config";

    /**
     * Date format for backup files.
     */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Mapper for reading and writing yaml.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));

    /**
     * Supplier of answers to questions.
     */
    private final Supplier<String> answerReader;

    /**
     * Constructor.
     *
     * @param answerReader supplier of answers to questions.
     */
    Main(Supplier<String> answerReader) {
        this.answerReader = answerReader;
    }

    /**
     * Constructor.
     */
    Main() {
        this(Main::readFromStdIn);
    }

    /**
     * Invoke me.  Expected args:
     * - no args = use the default config file.
     * - 1 arg = the name of the config file to read and write.
     * - >1 args = error.
     *
     * @param args optional: the config file to read and write.
     */
    public static void main(String[] args) {
        try {
            new Main().go(args);
        } catch (Exception e) {
            System.err.println("uh oh :(");
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Answer supplier that reads input from standard in.
     *
     * @return the answer
     */
    private static String readFromStdIn() {
        try {
            // Don't use try-with-resources because we don't want to close System.in
            return new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Do it.
     *
     * @param args the program args passed in.
     * @throws Exception if something bad happens.
     */
    void go(String[] args) throws Exception {

        // Get the name of the config file to read
        final Path configFile = getConfigFile(args);

        // Create a backup
        createConfigFileBackup(configFile);

        // Read the config
        ObjectNode config = (ObjectNode) MAPPER.readTree(configFile.toUri().toURL());

        // Process the config
        process(config);

        // Output the modified config
        System.out.println();
        System.out.println("Final config is:\n" + MAPPER.writeValueAsString(config));
        System.out.println();

        // Write back the config
        writeConfigFile(configFile, config);
    }

    /**
     * Helper method to create {@link Set} of answers.
     *
     * @param answers var arg of answers.
     * @return set of answers.
     */
    private Set<String> answers(String... answers) {
        return Arrays.stream(answers).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /**
     * Create a backup of the config file.
     *
     * @param configFile the config file to backup.
     * @throws IOException if something goes wrong.
     */
    private void createConfigFileBackup(Path configFile) throws IOException {
        String configFilename = configFile.toAbsolutePath().toString();
        boolean createBackup = yesOrNo("Create backup of '" + configFilename + "'?");
        if (createBackup) {
            Path backup = Paths.get(configFilename + ".backup." + DATE_FORMAT.format(LocalDateTime.now()));
            Files.copy(configFile, backup, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Created backup: " + backup);
        }
    }

    /**
     * Get the config file.
     *
     * @param args program args
     * @return the config file.
     * @throws FileNotFoundException if the config file is not found.
     */
    private Path getConfigFile(String[] args) throws FileNotFoundException {
        // Get name of file to read
        final String configFilename = getConfigFilename(args);
        System.out.println("Reading '" + configFilename + "'");
        Path configFile = Paths.get(configFilename);
        if (!Files.exists(configFile)) {
            throw new FileNotFoundException(configFilename);
        }
        return configFile;
    }

    /**
     * Get the config filename to use.
     *
     * @param args program args.
     * @return the config filename.
     */
    private String getConfigFilename(String[] args) {
        if (args.length == 0) {
            return DEFAULT_CONFIG_FILENAME;
        } else if (args.length == 1) {
            return args[0];
        } else {
            System.err.println("At most 1 argument allowed");
            System.exit(1);
            // Just to make the compiler happy.
            return null;
        }
    }

    /**
     * Delete unwanted contexts from the config.
     *
     * @param config the config.
     * @return a list of the contexts to delete.
     * @throws IOException if something goes wrong.
     */
    private List<ContextInfo> deleteContexts(final ObjectNode config) throws IOException {
        ArrayNode contexts = (ArrayNode) config.get("contexts");
        int index = 0;
        List<ContextInfo> contextsToDelete = new ArrayList<>();
        for (JsonNode context : contexts) {
            String name = context.at("/name").asText();
            String cluster = context.at("/context/cluster").asText();
            String user = context.at("/context/user").asText();
            System.out.println();
            System.out.println("=== Context: '" + name + "' ===");
            System.out.println("cluster: " + cluster);
            System.out.println("user   : " + user);
            // Ask if this context is to be deleted. If yes, make a note of the index.
            boolean delete = yesOrNo("Delete context '" + name + "'?", false);
            if (delete) {
                contextsToDelete.add(ContextInfo.of(index, cluster, user));
            }
            index++;
        }

        // Delete the identified contexts
        contextsToDelete.forEach(e -> contexts.remove(e.contextIndex));

        return contextsToDelete;
    }

    private void deleteClusters(final ObjectNode config, List<ContextInfo> deletedContexts) {

        // Get the indexes of the clusters to delete.
        Set<String> clusterNamesToDelete = deletedContexts.stream().map(e -> e.clusterName).collect(Collectors.toSet());
        ArrayNode clusters = (ArrayNode) config.get("clusters");
        int index = 0;
        List<Integer> clusterIndexesToDelete = new ArrayList<>();
        for (JsonNode cluster : clusters) {
            String name = cluster.at("/name").asText();
            if (clusterNamesToDelete.contains(name)) {
                clusterIndexesToDelete.add(index);
                System.out.println("Deleting cluster: '" + name + "'");
            }
            index++;
        }

        // Delete the clusters from the config.
        clusterIndexesToDelete.forEach(clusters::remove);
    }

    private void deleteUsers(ObjectNode config, List<ContextInfo> deletedContexts) {

        // Get the indexes of the users to delete.
        Set<String> userNamesToDelete = deletedContexts.stream().map(e -> e.userName).collect(Collectors.toSet());
        ArrayNode users = (ArrayNode) config.get("users");
        int index = 0;
        List<Integer> userIndexesToDelete = new ArrayList<>();
        for (JsonNode user : users) {
            String name = user.at("/name").asText();
            if (userNamesToDelete.contains(name)) {
                userIndexesToDelete.add(index);
                System.out.println("Deleting user: '" + name + "'");
            }
            index++;
        }

        // Delete the users from the config.
        userIndexesToDelete.forEach(users::remove);
    }

    /**
     * Process the config to remove unwanted contexts.
     *
     * @param config the config.
     * @throws IOException if something goes wrong.
     */
    private void process(final ObjectNode config) throws IOException {

        // Get the contexts to delete
        List<ContextInfo> deletedContexts = deleteContexts(config);

        // Delete clusters associated with the deleted contexts.
        deleteClusters(config, deletedContexts);

        // Delete users associated with the deleted contexts.
        deleteUsers(config, deletedContexts);
    }

    /**
     * Ask a question on System out and return the answer.
     *
     * @param question the question to ask.
     * @param answers the acceptable answers. May be null.
     * @param defaultAnswer the default answer to return if there is no input. May be null.
     * @return the answer to the question.
     */
    private String questionAndAnswer(String question, Set<String> answers, String defaultAnswer) {

        /* Input checks */
        // Need a non-null question.
        Objects.requireNonNull(question);
        if (CollectionUtils.isNotEmpty(answers)) {
            // Make sure any default answer is in the acceptable answers.
            if (StringUtils.isNotBlank(defaultAnswer) && !answers.contains(defaultAnswer)) {
                throw new IllegalArgumentException("Default answer (" + defaultAnswer + ") is not in set of answers " + answers);
            }
            // Should be more than one answer, or else why ask a question.
            if (answers.size() <= 1) {
                throw new IllegalArgumentException("Need more answers than " + answers);
            }
        }

        // Compile the question and possible answers
        StringBuilder fullQuestion = new StringBuilder().append(question);
        if (CollectionUtils.isNotEmpty(answers)) {
            fullQuestion.append(" ( ");
            for (Iterator<String> i = answers.iterator(); i.hasNext(); ) {
                String next = i.next();
                if (next.equals(defaultAnswer)) {
                    // Indicate that this is default answer with '*'
                    fullQuestion.append(next).append("*");
                } else {
                    fullQuestion.append(next);
                }
                if (i.hasNext()) {
                    fullQuestion.append(" | ");
                }
            }
            fullQuestion.append(" )");
        }
        fullQuestion.append(" : ");

        // Loop until we get an answer
        String answer = null;
        while (StringUtils.isBlank(answer)) {

            // Ask the question
            System.out.println(fullQuestion);

            // Read answer
            String input = this.answerReader.get();

            if (StringUtils.isBlank(input)) {
                // Blank input. Use the default answer if given.
                if (StringUtils.isNotBlank(defaultAnswer)) {
                    answer = defaultAnswer;
                }
            } else {
                // Non-blank input.
                if (CollectionUtils.isEmpty(answers)) {
                    // Don't need to compare the answer with acceptable answers. Just return what was input.
                    answer = input;
                } else {
                    // Need to compare the answer with acceptable answers.
                    if (answers.contains(input)) {
                        answer = input;
                    } else {
                        System.err.println("'" + input + "' is not in " + answers);
                    }
                }
            }
        }

        return answer;
    }

    /**
     * Write the modified config back to file.
     *
     * @param configFile the config file to write to.
     * @param config the config to write.
     * @throws IOException if something goes wrong.
     */
    private void writeConfigFile(Path configFile, ObjectNode config) throws IOException {
        String configFilename = configFile.toAbsolutePath().toString();
        boolean writeConfig = yesOrNo("Write updated config back to '" + configFilename + "'?", false);
        if (writeConfig) {
            try (FileOutputStream fos = new FileOutputStream(configFile.toAbsolutePath().toString())) {
                MAPPER.writeValue(fos, config);
            }
        }
    }

    /**
     * Ask a yes/no question.
     *
     * @param question the question to ask.
     * @param yesIsDefault true if yes is the default answer, false if no is the default.
     * @return true if answered yes, false if answered no.
     * @throws IOException if something goes wrong.
     */
    private boolean yesOrNo(String question, boolean yesIsDefault) throws IOException {
        String answer = questionAndAnswer(question, answers("y", "n"), yesIsDefault ? "y" : "n");
        return "y".equals(answer);
    }

    /**
     * Ask a yes/no question where yes is the default answer.
     *
     * @param question the question to ask.
     * @return true if answered yes, false if answered no.
     * @throws IOException if something goes wrong.
     */
    private boolean yesOrNo(String question) throws IOException {
        return yesOrNo(question, true);
    }
}
