package dev.recall.service.embedding;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * all-MiniLM-L6-v2 running in-process through ONNX Runtime: WordPiece-tokenize, run the transformer, mean-pool the
 * token vectors over the attention mask and L2-normalize (the sentence-transformers recipe for this model).
 * The model files are read from disk once; nothing is downloaded at runtime.
 */
public final class OnnxEmbeddingService implements EmbeddingService, AutoCloseable {

    /** The model was trained on sequences of up to 256 tokens. */
    static final int MAX_TOKENS = 256;
    static final String MODEL_FILE = "model.onnx";
    static final String VOCAB_FILE = "vocab.txt";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final BertTokenizer tokenizer;
    private final boolean wantsTokenTypes;

    private OnnxEmbeddingService(OrtEnvironment env, OrtSession session, BertTokenizer tokenizer) throws OrtException {
        this.env = env;
        this.session = session;
        this.tokenizer = tokenizer;
        this.wantsTokenTypes = session.getInputNames().contains("token_type_ids");
    }

    public static OnnxEmbeddingService load(Path modelDir) {
        Path model = modelDir.resolve(MODEL_FILE);
        Path vocab = modelDir.resolve(VOCAB_FILE);
        if (!Files.isRegularFile(model) || !Files.isRegularFile(vocab)) {
            throw new IllegalStateException("Embedding model not found in " + modelDir.toAbsolutePath()
                    + ". Run scripts/fetch-model.sh (or scripts/fetch-model.ps1), or start with the 'offline' profile"
                    + " to use hashing embeddings.");
        }
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(2);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            OrtSession session = env.createSession(model.toString(), options);
            BertTokenizer tokenizer = BertTokenizer.fromVocab(Files.readAllLines(vocab, StandardCharsets.UTF_8));
            return new OnnxEmbeddingService(env, session, tokenizer);
        } catch (OrtException | IOException e) {
            throw new IllegalStateException("Could not load the embedding model from " + modelDir.toAbsolutePath(), e);
        }
    }

    @Override
    public float[] embed(String text) {
        BertTokenizer.Encoding encoding = tokenizer.encode(text, MAX_TOKENS);
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(env, new long[][] {encoding.ids()}));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, new long[][] {encoding.attentionMask()}));
            if (wantsTokenTypes) {
                inputs.put("token_type_ids", OnnxTensor.createTensor(env, new long[][] {encoding.tokenTypeIds()}));
            }
            try (OrtSession.Result result = session.run(inputs)) {
                Optional<OnnxValue> named = result.get("last_hidden_state");
                OnnxValue hidden = named.isPresent() ? named.get() : result.get(0);
                float[][] tokens = ((float[][][]) hidden.getValue())[0];
                return Vectors.normalize(meanPool(tokens, encoding.attentionMask()));
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed", e);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    static float[] meanPool(float[][] tokens, long[] mask) {
        float[] pooled = new float[DIMENSIONS];
        int count = 0;
        for (int t = 0; t < tokens.length; t++) {
            if (mask[t] == 0) {
                continue;
            }
            count++;
            for (int d = 0; d < DIMENSIONS; d++) {
                pooled[d] += tokens[t][d];
            }
        }
        if (count > 0) {
            for (int d = 0; d < DIMENSIONS; d++) {
                pooled[d] /= count;
            }
        }
        return pooled;
    }

    @Override
    public String name() {
        return "all-MiniLM-L6-v2";
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
