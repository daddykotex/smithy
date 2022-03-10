/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.cli.commands;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.smithy.cli.Arguments;
import software.amazon.smithy.cli.CliError;
import software.amazon.smithy.cli.Colors;
import software.amazon.smithy.cli.Command;
import software.amazon.smithy.cli.Parser;
import software.amazon.smithy.cli.SmithyCli;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.SmithyIdlModelSerializer;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.utils.SetUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public final class TransformAwsSdkModelsCommand implements Command {
    @Override
    public String getName() {
        return "transform_aws_sdk";
    }

    @Override
    public String getSummary() {
        return "Transforms AWS SDK Smithy models";
    }

    @Override
    public Parser getParser() {
        return Parser.builder()
                .option(SmithyCli.ALLOW_UNKNOWN_TRAITS, "Ignores unknown traits when validating models")
                .option(SmithyCli.DISCOVER, "-d", "Enables model discovery, merging in models found inside of jars")
                .parameter(SmithyCli.DISCOVER_CLASSPATH, "Enables model discovery using a custom classpath for models")
                .parameter(SmithyCli.SEVERITY, "Sets a minimum validation event severity to display. "
                                               + "Defaults to NOTE. Can be set to SUPPRESSED, NOTE, WARNING, "
                                               + "DANGER, ERROR.")
                .positional("<MODELS>", "Path to Smithy models or directories")
                .build();
    }

    @Override
    public void execute(Arguments arguments, ClassLoader classLoader) {
        List<String> models = arguments.positionalArguments();
        //models expected to be a single file or directories to scan where 1 file per directory
        List<String> modelFiles = new LinkedList<>();
        for (String model : models) {
            File modelFile = new File(model);
            File[] fileArray = modelFile.listFiles(new FileFilter() {
                @Override
                public boolean accept(File pathname) {
                    return pathname.getName().endsWith(".json") || pathname.getName().endsWith(".smithy");
                }
            });
            if (fileArray != null) {
                for (File f : fileArray) {
                    modelFiles.add(f.getAbsolutePath());
                }
            }
        }

        final Path outputDir = CommandUtils.handleOutputDirectory(arguments).orElseGet(() -> Path.of("."));
        final Path reportFileOutput = outputDir.resolve("report.out");
        try {
            final BufferedWriter reportWriter = Files.newBufferedWriter(reportFileOutput, StandardCharsets.UTF_8);
            for (String file : modelFiles) {
                Colors.BRIGHT_WHITE.out(String.format("AWS SDK Smithy model sources: %s", models));
                try {
                    final Model convertedModel = CommandUtils
                            .buildModelForSingleFile(file, arguments, classLoader, SetUtils.of());
                    final String serviceName = getServiceName(convertedModel);
                    //filenames of errors
                    final Path errorPathOut = outputDir.resolve(serviceName.toLowerCase() + ".errors");
                    try {
                        ModelAssembler assembler = new ModelAssembler()
                                .addModel(convertedModel);
                        ValidatedResult<Model> modelValidatedResult = assembler.assemble();
                        writeErrors(errorPathOut, modelValidatedResult);
                        final List<ValidationEvent> errors = modelValidatedResult.getValidationEvents().stream()
                                .filter(result -> result.getSeverity() == Severity.ERROR
                                                    || result.getSeverity() == Severity.DANGER)
                            .collect(Collectors.toList());
                        if (!errors.isEmpty()) {
                            reportWriter.write("Service **" + serviceName
                                + "** failures converting to Smithy IDL 2.0" + System.lineSeparator());
                            for (ValidationEvent event:errors) {
                                reportWriter.write("* " + event.toString() + System.lineSeparator());
                            }
                            reportWriter.write(System.lineSeparator());
                        }
                        Model serializingModel = null;
                        try {
                            serializingModel = modelValidatedResult.unwrap();
                        } catch (Exception e) {
                            continue;
                        }
                        final Map<Path, String> idlFiles = SmithyIdlModelSerializer.builder()
                                // Only write shapes that came from the Coral models.
                                .shapeFilter(s -> convertedModel.getShape(s.getId()).isPresent())
                                .build()
                                .serialize(serializingModel);

                        //there should only be a single file out that is associated with the service
                        final Map.Entry<Path, String> mainIdlFile = getServiceFile(idlFiles);
                        final Path pathOut = getOutputServiceFilename(outputDir, mainIdlFile);

                        writeCurrentSmithyVersionOuput(pathOut, mainIdlFile.getValue());
                        System.out.println("Service file written out: " + pathOut.toFile().getAbsolutePath());
                    } catch (Exception e) {
                        System.out.printf("Exception on service {%s, %s} :: %s%n", serviceName, file, e.getMessage());
                        e.printStackTrace(System.out);
                        continue;
                    }
                } catch (Exception e) {
                    System.out.printf("Exception on file {%s} :: %s%n", file, e.getMessage());
                    e.printStackTrace(System.out);
                }
            }
            reportWriter.close();
        } catch (IOException e) {
            throw new RuntimeException("Could not open file for output reporting: " + reportFileOutput.toString(), e);
        }
    }

    private void writeCurrentSmithyVersionOuput(final Path outPath, final String modelText) {
        try {
            Files.writeString(outPath, modelText, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CliError("Could not write model out for: " + outPath.toFile().getAbsolutePath());
        }
    }

    private void writeErrors(Path errorPathOut, ValidatedResult<Model> modelValidatedResult) {
        if (!modelValidatedResult.getValidationEvents().isEmpty()) {
            try (StringWriter content = new StringWriter()) {
                for (ValidationEvent event : modelValidatedResult.getValidationEvents()) {
                    content.write(String.format("%s%n", event.toString()));
                }
                Files.writeString(errorPathOut, content.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CliError("Could not write errors out for: " + errorPathOut.toFile().getAbsolutePath());
            }
            Colors.RED.out("Errors file written out: " + errorPathOut.toFile().getAbsolutePath());
        }
    }

    private Path getOutputServiceFilename(final Path parentDir, final Entry<Path, String> mainIdlFile) {
        final String lastPart = mainIdlFile.getKey().toFile().getName();
        return parentDir.resolve(lastPart.substring("com.amazonaws.".length()));
    }

    private String getServiceName(final Model convertedModel) {
        final Set<ServiceShape> serviceShapes = convertedModel.getServiceShapes();
        if (serviceShapes.size() != 1) {
            throw new RuntimeException(String.format("Zero or more than one service shapes {%d} found.",
                    serviceShapes.size()));
        }
        final ServiceShape shape = serviceShapes.iterator().next();
        return shape.getId().getName();
    }

    private static Map.Entry<Path, String> getServiceFile(final Map<Path, String> files) {
        List<Map.Entry<Path, String>> idlFile = files.entrySet().stream()
                .filter(entry -> entry.getKey().toFile().getName().startsWith("com.amazonaws"))
                .collect(Collectors.toList());
        if (idlFile.size() != 1) {
            throw new RuntimeException(String.format("Could not find exactly one {%d} service.", idlFile.size()));
        }
        return idlFile.get(0);
    }
}
