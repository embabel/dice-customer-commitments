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
import org.drivine.manager.PersistenceManagerFactory;
import org.drivine.schema.SchemaCatalog;
import org.drivine.schema.UniquenessConstraintSpec;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
            var file = Path.of(environment.getRequiredProperty("database.datasources.local.database-name"));
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
    SchemaCatalog commitmentsCatalog() {
        return SchemaCatalog.of(List.of(new UniquenessConstraintSpec("CommitmentsBaseline", "id")))
                .forDatabase(DATABASE);
    }

    @Bean
    CommitmentStore commitmentStore(PropositionRepository raw, PersistenceManagerFactory factory,
                              PlatformTransactionManager transactionManager) {
        if (!(raw instanceof DrivinePropositionRepository repository)) {
            throw new IllegalStateException("Customer Commitments requires the DICE SQLite store");
        }
        var manager = factory.get(DATABASE);
        // The fixture records one correction at the known receipt time. Replay stays deterministic.
        var clock = Clock.fixed(AMENDMENT, ZoneOffset.UTC);
        return new CommitmentStore(repository, new DrivineAuditLogStore(manager, transactionManager, clock), manager);
    }

    @Bean
    LlmService<?> recordedExtractionModel() {
        return new SpringAiLlmService("recorded-extraction", "customer-commitments", new RecordedExtraction());
    }

    @Bean
    EmbeddingService demoEmbeddings() {
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
        // Both the agreement and replay use exact context reads. No decision uses this fixture vector.
        return new SpringAiEmbeddingService("demo-embeddings", "customer-commitments", model, 3, null);
    }
}
