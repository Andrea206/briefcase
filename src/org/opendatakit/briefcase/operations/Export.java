/*
 * Copyright (C) 2018 Nafundi
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.briefcase.operations;

import static java.time.format.DateTimeFormatter.ISO_DATE_TIME;
import static org.opendatakit.briefcase.export.ExportForms.buildExportDateTimePrefix;
import static org.opendatakit.briefcase.operations.Common.FORM_ID;
import static org.opendatakit.briefcase.operations.Common.STORAGE_DIR;
import static org.opendatakit.briefcase.operations.Common.bootCache;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bouncycastle.openssl.PEMReader;
import org.bushe.swing.event.EventBus;
import org.opendatakit.briefcase.model.BriefcaseFormDefinition;
import org.opendatakit.briefcase.model.BriefcasePreferences;
import org.opendatakit.briefcase.model.ExportFailedEvent;
import org.opendatakit.briefcase.model.ExportProgressEvent;
import org.opendatakit.briefcase.ui.export.ExportPanel;
import org.opendatakit.briefcase.util.ExportToCsv;
import org.opendatakit.briefcase.util.FileSystemUtils;
import org.opendatakit.common.cli.Operation;
import org.opendatakit.common.cli.Param;

public class Export {
  private static final Log log = LogFactory.getLog(Export.class);
  private static final Param<Void> EXPORT = Param.flag("e", "export", "Export a form");
  private static final Param<Path> EXPORT_DIR = Param.arg("ed", "export_directory", "Export directory", Paths::get);
  private static final Param<String> FILE = Param.arg("f", "export_filename", "Filename for export operation");
  private static final Param<LocalDate> START = Param.arg("start", "export_start_date", "Export start date", LocalDate::parse);
  private static final Param<LocalDate> END = Param.arg("end", "export_end_date", "Export end date", LocalDate::parse);
  private static final Param<Void> EXCLUDE_MEDIA = Param.flag("em", "exclude_media_export", "Exclude media in export");
  private static final Param<Void> OVERWRITE = Param.flag("oc", "overwrite_csv_export", "Overwrite files during export");
  private static final Param<Path> PEM_FILE = Param.arg("pf", "pem_file", "PEM file for form decryption", Paths::get);

  public static Operation EXPORT_FORM = Operation.of(
      EXPORT,
      args -> export(
          args.get(STORAGE_DIR),
          args.get(FORM_ID),
          args.get(EXPORT_DIR),
          args.getOptional(START),
          args.getOptional(END),
          args.getOptional(PEM_FILE)
      ),
      Arrays.asList(STORAGE_DIR, FORM_ID, FILE, EXPORT_DIR),
      Arrays.asList(PEM_FILE, EXCLUDE_MEDIA, OVERWRITE, START, END)
  );

  public static void export(String storageDir, String formid, Path exportPath, Optional<LocalDate> startDate, Optional<LocalDate> endDate, Optional<Path> pemFile) {
    CliEventsCompanion.attach(log);
    bootCache(storageDir);
    Optional<BriefcaseFormDefinition> maybeFormDefinition = FileSystemUtils.getBriefcaseFormList().stream()
        .filter(form -> form.getFormId().equals(formid))
        .findFirst();

    if (!maybeFormDefinition.isPresent()) {
      EventBus.publish(new FormNotFoundEvent(formid));
      throw new FormNotFoundException(formid);
    }

    BriefcaseFormDefinition formDefinition = maybeFormDefinition.get();

    if (formDefinition.isFileEncryptedForm() || formDefinition.isFieldEncryptedForm()) {
      if (!pemFile.isPresent()) {
        EventBus.publish(new WrongExportConfigurationEvent(formid, "Missing pem file configuration"));
        throw new ExportException(formid, "Missing pem file configuration");
      }

      if (!Files.exists(pemFile.get())) {
        EventBus.publish(new WrongExportConfigurationEvent(formid, "Pem file doesn't exist"));
        throw new ExportException(formid, "Missing pem file configuration");
      }

      try (PEMReader rdr = new PEMReader(new BufferedReader(new InputStreamReader(Files.newInputStream(pemFile.get()), "UTF-8")))) {
        Optional<Object> o = Optional.ofNullable(rdr.readObject());
        if (!o.isPresent()) {
          EventBus.publish(new WrongExportConfigurationEvent(formid, "Can't parse Pem file"));
          throw new ExportException(formid, "Can't parse Pem file");
        }

        Optional<PrivateKey> privKey;
        if (o.get() instanceof KeyPair) {
          privKey = Optional.of(((KeyPair) o.get()).getPrivate());
        } else if (o.get() instanceof PrivateKey) {
          privKey = Optional.of((PrivateKey) o.get());
        } else {
          privKey = Optional.empty();
        }

        if (!privKey.isPresent()) {
          EventBus.publish(new WrongExportConfigurationEvent(formid, "No private key found on Pem file"));
          throw new ExportException(formid, "No private key found on Pem file");
        }

        formDefinition.setPrivateKey(privKey.get());
        EventBus.publish(new ExportProgressEvent("Successfully parsed Pem file", formDefinition));
      } catch (IOException e) {
        EventBus.publish(new ExportFailedEvent(formDefinition));
        throw new ExportException(formid, "Can't parse Pem file");
      }
    }

    System.out.println("Exporting form " + formDefinition.getFormName() + " (" + formDefinition.getFormId() + ") to: " + exportPath);
    ExportToCsv.export(exportPath, formDefinition, startDate, endDate);

    BriefcasePreferences.forClass(ExportPanel.class).put(buildExportDateTimePrefix(formDefinition.getFormId()), LocalDateTime.now().format(ISO_DATE_TIME));
  }
}
