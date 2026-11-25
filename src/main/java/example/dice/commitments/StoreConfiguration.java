package example.dice.commitments;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.SpringAiEmbeddingService;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.storage.AuditLogSchema;
import com.embabel.dice.storage.DiceStorageSchema;
import com.embabel.dice.storage.DiceStorageSchemaKt;
import com.embabel.dice.storage.DrivineAuditLogStore;
import com.embabel.dice.storage.DrivinePropositionRepository;
import org.drivine.connection.ConnectionProperties;
import org.drivine.manager.PersistenceManager;
import org.drivine.schema.SchemaCatalog;
import org.drivine.schema.UniquenessConstraintSpec;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

@Configuration(proxyBeanMethods = false)
class StoreConfiguration {

    static final Instant OPENING = Instant.parse("2026-11-18T15:00:00Z");
    static final Instant AMENDMENT = Instant.parse("2026-11-18T15:22:00Z");
    private static final String DATABASE = "local";

    @Bean
    static BeanFactoryPostProcessor createStorageDirectory(Environment environment) {
        return factory -> {
            // The pool needs the directory before Spring creates the datasource beans.
            var connection = Binder.get(environment)
                    .bind("database.datasources." + DATABASE, ConnectionProperties.class)
                    .orElseThrow(() -> new IllegalStateException("Missing SQLite datasource " + DATABASE));
            var file = Path.of(connection.getDatabaseName());
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
            } catch (IOException failure) {
                throw new IllegalStateException("Could not create the SQLite storage directory", failure);
            }
        };
    }

    @Bean
    DiceStorageSchema auditSchema() {
        return AuditLogSchema.forRegistration();
    }

    @Bean
    SchemaCatalog auditCatalog(@Qualifier("auditSchema") DiceStorageSchema schema) {
        return DiceStorageSchemaKt.diceStorageCatalog(List.of(schema)).forDatabase(DATABASE);
    }

    @Bean
    @Profile("!live")
    SchemaCatalog commitmentsCatalog() {
        return SchemaCatalog.of(List.of(new UniquenessConstraintSpec("CommitmentsBaseline", "id")))
                .forDatabase(DATABASE);
    }

    @Bean
    @Profile("!live")
    DrivineAuditLogStore auditLog(PersistenceManager manager, PlatformTransactionManager transactions) {
        // The fixture records one correction at the known receipt time.
        return new DrivineAuditLogStore(manager, transactions, Clock.fixed(AMENDMENT, ZoneOffset.UTC));
    }

    @Bean
    @Profile("!live")
    CommitmentStore commitmentStore(PropositionRepository raw, DrivineAuditLogStore audit,
                                    PersistenceManager manager) {
        if (!(raw instanceof DrivinePropositionRepository repository)) {
            throw new IllegalStateException("Customer Commitments requires the DICE SQLite store");
        }
        return new CommitmentStore(repository, audit, manager);
    }

    @Bean
    @Profile("!live")
    LlmService<?> recordedExtractionModel() {
        return new SpringAiLlmService("recorded-extraction", "customer-commitments", new RecordedExtraction());
    }

    @Bean
    @Primary
    EmbeddingService storageEmbeddings() {
        var model = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                return new EmbeddingResponse(IntStream.range(0, request.getInstructions().size())
                        .mapToObj(index -> new Embedding(new float[]{1, 0, 0}, index)).toList());
            }

            @Override
            public float[] embed(Document document) {
                return new float[]{1, 0, 0};
            }
        };
        // Both profiles use exact-context reads. This local vector keeps repository writes independent of model calls.
        return new SpringAiEmbeddingService("demo-embeddings", "customer-commitments", model, 3, null);
    }
}
