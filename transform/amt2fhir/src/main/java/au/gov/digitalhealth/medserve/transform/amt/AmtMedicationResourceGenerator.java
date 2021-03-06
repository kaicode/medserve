package au.gov.digitalhealth.medserve.transform.amt;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Pair;
import org.hl7.fhir.dstu3.model.Annotation;
import org.hl7.fhir.dstu3.model.CodeableConcept;
import org.hl7.fhir.dstu3.model.Coding;
import org.hl7.fhir.dstu3.model.ContactPoint;
import org.hl7.fhir.dstu3.model.DateType;
import org.hl7.fhir.dstu3.model.DecimalType;
import org.hl7.fhir.dstu3.model.DomainResource;
import org.hl7.fhir.dstu3.model.Enumeration;
import org.hl7.fhir.dstu3.model.Medication;
import org.hl7.fhir.dstu3.model.Medication.MedicationPackageComponent;
import org.hl7.fhir.dstu3.model.Medication.MedicationPackageContentComponent;
import org.hl7.fhir.dstu3.model.Medication.MedicationStatus;
import org.hl7.fhir.dstu3.model.Medication.MedicationStatusEnumFactory;
import org.hl7.fhir.dstu3.model.Narrative;
import org.hl7.fhir.dstu3.model.Narrative.NarrativeStatus;
import org.hl7.fhir.dstu3.model.Organization;
import org.hl7.fhir.dstu3.model.Quantity;
import org.hl7.fhir.dstu3.model.Ratio;
import org.hl7.fhir.dstu3.model.Reference;
import org.hl7.fhir.dstu3.model.Resource;
import org.hl7.fhir.dstu3.model.SimpleQuantity;
import org.hl7.fhir.dstu3.model.StringType;
import org.hl7.fhir.dstu3.model.Substance.FHIRSubstanceStatus;
import org.hl7.fhir.dstu3.model.UriType;

import au.gov.digitalhealth.medserve.extension.ExtendedMedication;
import au.gov.digitalhealth.medserve.extension.ExtendedReference;
import au.gov.digitalhealth.medserve.extension.ExtendedSubstance;
import au.gov.digitalhealth.medserve.extension.GeneralizedMedication;
import au.gov.digitalhealth.medserve.extension.IsReplacedByExtension;
import au.gov.digitalhealth.medserve.extension.MedicationIngredientComponentExtension;
import au.gov.digitalhealth.medserve.extension.MedicationParentExtension;
import au.gov.digitalhealth.medserve.extension.ParentExtendedElement;
import au.gov.digitalhealth.medserve.extension.ReplacesResourceExtension;
import au.gov.digitalhealth.medserve.extension.ResourceWithHistoricalAssociations;
import au.gov.digitalhealth.medserve.extension.SourceCodeSystemExtension;
import au.gov.digitalhealth.medserve.extension.SubsidyExtension;
import au.gov.digitalhealth.medserve.transform.amt.cache.AmtCache;
import au.gov.digitalhealth.medserve.transform.amt.cache.PbsCodeSystemUtil;
import au.gov.digitalhealth.medserve.transform.amt.enumeration.AmtConcept;
import au.gov.digitalhealth.medserve.transform.amt.enumeration.AttributeType;
import au.gov.digitalhealth.medserve.transform.amt.model.Concept;
import au.gov.digitalhealth.medserve.transform.amt.model.DataTypeProperty;
import au.gov.digitalhealth.medserve.transform.amt.model.Manufacturer;
import au.gov.digitalhealth.medserve.transform.amt.model.Relationship;
import au.gov.digitalhealth.medserve.transform.amt.model.Subsidy;
import au.gov.digitalhealth.medserve.transform.processor.MedicationResourceProcessor;
import au.gov.digitalhealth.medserve.transform.util.FhirCodeSystemUri;
import au.gov.digitalhealth.medserve.transform.util.FileUtils;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.validation.FhirValidator;

public class AmtMedicationResourceGenerator {
    private static final Logger logger = Logger.getLogger(AmtMedicationResourceGenerator.class.getCanonicalName());

    private static final String AMT_FILE_PATTERN =
            "NCTS_SCT_RF2_DISTRIBUTION_32506021000036107-(\\d{8})-SNAPSHOT\\.zip";
    private static Pattern amtFilePattern = Pattern.compile(AMT_FILE_PATTERN);

    private AmtCache conceptCache;
    private Set<String> processedConcepts = new HashSet<>();
    private String amtVersion;

    FhirValidator validator = FhirContext.forDstu3().newValidator();

    private Map<Long, Reference> referenceCache = new HashMap<>();

    private Map<Long, ExtendedReference> extendedReferenceCache = new HashMap<>();

    public AmtMedicationResourceGenerator(Path amtReleaseZipPath, Path pbsExtractPath)
            throws IOException {
        Matcher amtFileNameMatcher = amtFilePattern.matcher(amtReleaseZipPath.getFileName().toString());
        if (!amtFileNameMatcher.matches()) {
            throw new IllegalArgumentException("AMT file name " + amtReleaseZipPath.getFileName()
                    + " does not match expected pattern " + AMT_FILE_PATTERN);
        }

        amtVersion = "http://snomed.info/sct?version=http%3A%2F%2Fsnomed.info%2Fsct%2F32506021000036107%2Fversion%2F"
                + amtFileNameMatcher.group(1);

        this.conceptCache =
                new AmtCache(FileUtils.getFileSystemForZipPath(amtReleaseZipPath),
                    FileUtils.getFileSystemForZipPath(pbsExtractPath));
    }

    public void process(MedicationResourceProcessor processor) throws IOException {
        processConceptList(conceptCache.getCtpps(), "CTPP", processor);

        logger.info("Mopping up concepts unreferenced by CTPPs");

        processConceptList(conceptCache.getTpps(), "TPP", processor);
        processConceptList(conceptCache.getMpps(), "MPP", processor);
        processConceptList(conceptCache.getTpuus(), "TPUU", processor);
        processConceptList(conceptCache.getMpuus(), "MPUU", processor);
        processConceptList(conceptCache.getMps(), "MP", processor);
        processConceptList(conceptCache.getSubstances(), "Substance", processor);

        logger.info("Finished creating " + processedConcepts.size() + " resources");
    }

    private void processConceptList(Map<Long, Concept> conceptList, String conceptType,
            MedicationResourceProcessor processor)
            throws IOException {
        logger.info("Processing " + conceptList.size() + " " + conceptType + " concepts");

        List<Resource> createdResources = new ArrayList<>();
        int processedConceptsStartingSize = processedConcepts.size();
        int counter = 0;
        for (Concept concept : conceptCache.getCtpps().values()) {
            counter++;
            createdResources.clear();
            if (!processedConcepts.contains(Long.toString(concept.getId()))) {
                createPackageResource(concept, createdResources);
                processor.processResources(createdResources);
            }
            if (counter % 1000 == 0) {
                logger.info("Processed " + counter + " " + conceptType + "s...");
            }
        }

        logger.info("Completed processing " + conceptList.size() + " " + conceptType + "s, added "
                + (processedConcepts.size() - processedConceptsStartingSize) + " resources");
    }

    private Reference createSubstanceResource(Concept concept, List<Resource> createdResources) {
        Reference reference = toReference(concept, "Substance");
        if (!processedConcepts.contains(Long.toString(concept.getId()))) {
            processedConcepts.add(Long.toString(concept.getId()));
            ExtendedSubstance substance = new ExtendedSubstance();
            setStandardResourceElements(concept, substance);
            substance.setSourceCodeSystem(
                new SourceCodeSystemExtension(new UriType(FhirCodeSystemUri.SNOMED_CT_SYSTEM_URI.getUri()),
                    new StringType(amtVersion)));
            addHistoicalAssociations(concept, substance, "Substance");

            substance.setStatus(concept.isActive() ? FHIRSubstanceStatus.ACTIVE : FHIRSubstanceStatus.ENTEREDINERROR);

            substance.setCode(concept.toCodeableConcept());
            substance.setStatus(concept.isActive() ? FHIRSubstanceStatus.ACTIVE : FHIRSubstanceStatus.ENTEREDINERROR);
            substance.setLastModified(new DateType(concept.getLastModified()));

            concept.getMultipleDestinations(AttributeType.IS_MODIFICATION_OF)
                .forEach(m -> substance.addIngredient().setSubstance(createSubstanceResource(m, createdResources)));
            createdResources.add(substance);
        }

        return reference;
    }

    private ExtendedMedication createBaseMedicationResource(Concept concept, List<Resource> createdResources) {
        ExtendedMedication medication = new ExtendedMedication();
        medication.setSourceCodeSystem(
            new SourceCodeSystemExtension(new UriType(FhirCodeSystemUri.SNOMED_CT_SYSTEM_URI.getUri()),
                new StringType(amtVersion)));
        setStandardResourceElements(concept, medication);

        addHistoicalAssociations(concept, medication, "Medication");

        medication.setLastModified(new DateType(concept.getLastModified()));

        medication.setCode(concept.toCodeableConcept());

        medication.setStatus(concept.getStatus());

        medication.setMedicationResourceType(concept.getMedicationType().getCode());

        addGeneralizedMedicineExtensions(concept, medication.getGeneralizedMedicine(), createdResources);

        addParentExtensions(concept, medication, new HashSet<>(), createdResources);

        medication.setIsBrand(concept.getMedicationType().isBranded());

        if (concept.isActive()) {
            medication.setStatus(MedicationStatus.ACTIVE);
        } else {
            medication.setStatus(MedicationStatus.ENTEREDINERROR);
        }

        Set<String> artgIds = conceptCache.getArtgId(concept.getId());
        if (artgIds != null) {
            for (String id : artgIds) {
                Coding codingDt = medication.getCode().addCoding();
                codingDt.setSystem(FhirCodeSystemUri.TGA_URI.getUri());
                codingDt.setCode(id);
            }
        }

        CodeableConcept brand = concept.getBrand();
        if (brand != null) {
            medication.setBrand(brand);
        }

        return medication;
    }

    private void addGeneralizedMedicineExtensions(Concept concept, List<GeneralizedMedication> list,
            List<Resource> createdResources) {
        Concept c = concept;

        if (c.hasAtLeastOneMatchingAncestor(AmtConcept.CTPP)) {
            c = concept.getLeafAncestor(AmtConcept.TPP, AmtConcept.CTPP);
            list.add(new GeneralizedMedication(toReference(c, "Medication"), c.getMedicationType().getCode()));
        }

        if (c.hasAtLeastOneMatchingAncestor(AmtConcept.TPP)) {
            c = concept.getLeafAncestor(AmtConcept.MPP, AmtConcept.TPP);
            list.add(new GeneralizedMedication(toReference(c, "Medication"), c.getMedicationType().getCode()));
        }

        if (c.hasAtLeastOneMatchingAncestor(AmtConcept.TPUU)) {
            c = concept.getLeafAncestor(AmtConcept.MPUU, AmtConcept.TPUU);
            list.add(new GeneralizedMedication(toReference(c, "Medication"), c.getMedicationType().getCode()));
        }

        if (c.hasAtLeastOneMatchingAncestor(AmtConcept.MPUU)) {
            c = concept.getLeafAncestor(AmtConcept.MP, AmtConcept.MPUU);
            list.add(new GeneralizedMedication(toReference(c, "Medication"), c.getMedicationType().getCode()));
        }
    }

    private void addHistoicalAssociations(Concept concept, ResourceWithHistoricalAssociations resource,
            String resourceType) {
        if (concept.getReplacementConcept() != null) {
            for (ImmutableTriple<Long, Concept, Date> replacement : concept.getReplacementConcept()) {
                String targetResourceType = replacement.middle.getResourceType();
                if (!targetResourceType.equals(resourceType)) {
                    logger.warning("AMT concept replacement " + replacement.middle + " for concept " + concept
                            + "is not of the same type!!!");
                }
                resource.getReplacementResources()
                    .add(new IsReplacedByExtension(toReference(replacement.middle, targetResourceType),
                        AmtConcept.fromId(replacement.left).toCoding(), new DateType(replacement.right)));
            }
        }

        if (concept.getReplacedConcept() != null) {
            for (ImmutableTriple<Long, Concept, Date> replaced : concept.getReplacedConcept()) {
                String targetResourceType = replaced.middle.getResourceType();
                if (!targetResourceType.equals(resourceType)) {
                    logger.warning("AMT concept replacement " + concept + " for concept " + replaced.middle
                            + "is not of the same type!!!");
                }
                resource.getReplacedResources()
                    .add(new ReplacesResourceExtension(toReference(replaced.middle, targetResourceType),
                        AmtConcept.fromId(replaced.left).toCoding(), new DateType(replaced.right)));
            }
        }
    }

    private void setStandardResourceElements(Concept concept, DomainResource resource) {
        resource.setId(Long.toString(concept.getId()));
        Narrative narrative = new Narrative();
        narrative.setStatus(NarrativeStatus.GENERATED);
        narrative.setDivAsString("<div><p>" + StringEscapeUtils.escapeHtml3(concept.getPreferredTerm()) + "</p></div>");
        resource.setText(narrative);
    }

    private void addParentExtensions(Concept concept, ParentExtendedElement element, Set<Long> addedConcepts,
            List<Resource> createdResources) {
        concept.getParents()
            .values()
            .stream()
            .filter(parent -> !AmtConcept.isEnumValue(Long.toString(parent.getId())))
            .filter(parent -> !addedConcepts.contains(parent.getId()))
            .forEach(parent -> {
                if (!parent.hasParent(AmtConcept.TP)) {
                    MedicationParentExtension extension = new MedicationParentExtension();
                    extension.setParentMedication(toReference(parent, "Medication"));
                    extension.setMedicationResourceType(parent.getMedicationType().getCode());
                    extension.setMedicationResourceStatus(
                        new Enumeration<MedicationStatus>(new MedicationStatusEnumFactory(), parent.getStatus()));
                    extension.setLastModified(new DateType(parent.getLastModified()));

                    addHistoicalAssociations(concept, extension, "Medication");

                    addedConcepts.add(parent.getId());
                    addParentExtensions(parent, extension, addedConcepts, createdResources);

                    switch (parent.getMedicationType()) {
                        case BrandedPackage:
                        case BrandedPackgeContainer:
                        case UnbrandedPackage:
                            createPackageResource(parent, createdResources);
                            break;

                        default:
                            createProductResource(parent, createdResources);
                            break;
                    }

                    element.addParentMedicationResources(extension);

                }
            });
    }

    private Reference createPackageResource(Concept concept, List<Resource> createdResources) {
        Reference reference = toExtendedMedicationReference(concept, createdResources);
        if (!processedConcepts.contains(Long.toString(concept.getId()))) {
            processedConcepts.add(Long.toString(concept.getId()));
            ExtendedMedication medication = createBaseMedicationResource(concept, createdResources);
            MedicationPackageComponent pkg = new MedicationPackageComponent();
            medication.setPackage(pkg);

            Concept container = concept.getSingleDestination(AttributeType.HAS_CONTAINER_TYPE);
            if (container != null) {
                pkg.setContainer(container.toCodeableConcept());
            }

            concept.getRelationships(AttributeType.HAS_MPUU)
                .forEach(r -> addProductReference(pkg, r, createdResources));
            concept.getRelationships(AttributeType.HAS_TPUU)
                .forEach(r -> addProductReference(pkg, r, createdResources));

            concept.getRelationships(AttributeType.HAS_COMPONENT_PACK)
                .forEach(r -> addProductReference(pkg, r, createdResources));
            concept.getRelationships(AttributeType.HAS_SUBPACK)
                .forEach(r -> addProductReference(pkg, r, createdResources));

            concept.getSubsidies().forEach(subsidy -> addSubsidy(medication, subsidy));

            if (concept.hasParent(AmtConcept.CTPP)) {
                concept.getParents()
                    .values()
                    .stream()
                    .filter(c -> c.hasAtLeastOneMatchingAncestor(AmtConcept.TPP)
                            && !c.hasAtLeastOneMatchingAncestor(AmtConcept.CTPP))
                    .flatMap(c -> c.getSubsidies().stream())
                    .forEach(subsidy -> addSubsidy(medication, subsidy));
            }

            Manufacturer manufacturer = concept.getManufacturer();
            if (manufacturer != null) {
                medication.setManufacturer(createOrganisation(createdResources, manufacturer));
            }

            createdResources.add(medication);
        }
        return reference;
    }

    private Reference createOrganisation(List<Resource> createdResources, Manufacturer manufacturer) {
        Reference orgRef = new Reference("Organization/" + manufacturer.getCode());
        orgRef.setDisplay(manufacturer.getName());
        if (!processedConcepts.contains(manufacturer.getCode())) {
            processedConcepts.add(manufacturer.getCode());
            Organization org = new Organization();
            org.setId(manufacturer.getCode());
            Narrative narrative = new Narrative();
            narrative.setDivAsString(StringEscapeUtils.escapeHtml3(manufacturer.getName()));
            narrative.setStatusAsString("generated");
            org.setText(narrative);
            org.addAddress().addLine(manufacturer.getAddress());
            org.setName(manufacturer.getName());
            ContactPoint cp = org.addTelecom();
            cp.setSystem(ContactPoint.ContactPointSystem.PHONE);
            cp.setUse(ContactPoint.ContactPointUse.WORK);
            cp.setValue(manufacturer.getPhone());
            if (manufacturer.getFax() != null) {
                ContactPoint fax = org.addTelecom();
                fax.setSystem(ContactPoint.ContactPointSystem.FAX);
                fax.setUse(ContactPoint.ContactPointUse.WORK);
                fax.setValue(manufacturer.getFax());
            }
            createdResources.add(org);
        }
        return orgRef;
    }

    private void addSubsidy(ExtendedMedication medication, Subsidy subsidy) {
        if (!medication.getSubsidies()
            .stream()
            .anyMatch(s -> s.getSubsidyCode().getCode().equals(subsidy.getPbsCode()))) {

            SubsidyExtension subsidyExt = new SubsidyExtension();
            subsidyExt
                .setSubsidyCode(new Coding(FhirCodeSystemUri.PBS_SUBSIDY_URI.getUri(), subsidy.getPbsCode(), null));
            subsidyExt.setProgramCode(new Coding(FhirCodeSystemUri.PBS_PROGRAM_URI.getUri(), subsidy.getProgramCode(),
                PbsCodeSystemUtil.getProgramCodeDisplay(subsidy.getProgramCode())));
            subsidyExt.setCommonwealthExManufacturerPrice(new DecimalType(subsidy.getCommExManPrice()));
            subsidyExt.setManufacturerExManufacturerPrice(new DecimalType(subsidy.getManExManPrice()));
            subsidyExt
                .setRestriction(new Coding(FhirCodeSystemUri.PBS_RESTRICTION_URI.getUri(), subsidy.getRestriction(),
                    PbsCodeSystemUtil.getRestrictionCodeDisplay(subsidy.getRestriction())));
            for (String note : subsidy.getNotes()) {
                subsidyExt.addNote(new Annotation(new StringType(note)));
            }
            for (String caution : subsidy.getCaution()) {
                subsidyExt.addCautionaryNote(new Annotation(new StringType(caution)));
            }

            for (Pair<String, String> atcCode : subsidy.getAtcCodes()) {
                CodeableConcept coding = subsidyExt.getAtcCode();
                if (coding == null) {
                    coding = new CodeableConcept();
                    subsidyExt.setAtcCode(coding);
                }
                Coding code = coding.addCoding();
                code.setCode(atcCode.getLeft());
                code.setDisplay(atcCode.getRight());
                code.setSystem(FhirCodeSystemUri.ATC_URI.getUri());
            }

            medication.getSubsidies().add(subsidyExt);
        }
    }

    private Reference createProductResource(Concept concept, List<Resource> createdResources) {
        Reference reference = toExtendedMedicationReference(concept, createdResources);
        if (!processedConcepts.contains(Long.toString(concept.getId()))) {
            processedConcepts.add(Long.toString(concept.getId()));
            ExtendedMedication medication = createBaseMedicationResource(concept, createdResources);
            concept.getRelationshipGroupsContaining(AttributeType.HAS_INTENDED_ACTIVE_INGREDIENT).forEach(
                r -> addIngredient(medication, r, createdResources));

            Concept form = concept.getSingleDestination(AttributeType.HAS_MANUFACTURED_DOSE_FORM);
            if (form != null) {
                medication.setForm(form.toCodeableConcept());
            }

            createdResources.add(medication);
        }
        return reference;
    }

    private void addProductReference(MedicationPackageComponent pkg, Relationship relationship,
            List<Resource> createdResources) {
        MedicationPackageContentComponent content = pkg.addContent();

        Concept destination = null;
        // This is a dirty hack because AMT doesn't restate the
        // HAS_COMPONENT_PACK and HAS_SUBPACK relationships from MPP on TPP. As
        // a result these relationships on TPPs are the inferred relationships
        // from MPP which target MPPs not TPPs. This code tries to back stitch
        // to the TPP which should be the target of a stated HAS_SUBPACK or
        // HAS_COMPONENT_PACK relationship, but the real solution is to state
        // these in AMT
        if ((relationship.getType().equals(AttributeType.HAS_SUBPACK)
                || relationship.getType().equals(AttributeType.HAS_COMPONENT_PACK))
                && relationship.getSource().hasParent(AmtConcept.TPP)) {
            Collection<Long> ctpp = conceptCache.getDescendantOf(relationship.getSource().getId());

            Set<Concept> destinationSet =
                    ctpp.stream()
                        .flatMap(c -> conceptCache.getConcept(c).getRelationships(relationship.getType()).stream())
                        .flatMap(r -> r.getDestination().getParents().values().stream())
                        .filter(c -> c.hasParent(relationship.getDestination()))
                        .collect(Collectors.toSet());

            if (destinationSet.size() != 1) {
                throw new RuntimeException("Destination set was expected to be 1 but was " + destinationSet);
            }

            destination = destinationSet.iterator().next();

        } else {
            destination = relationship.getDestination();
        }

        if (relationship.getType().equals(AttributeType.HAS_SUBPACK)
                || relationship.getType().equals(AttributeType.HAS_COMPONENT_PACK)) {
            content.setItem(createPackageResource(destination, createdResources));
        } else if (relationship.getType().equals(AttributeType.HAS_MPUU)
                || relationship.getType().equals(AttributeType.HAS_TPUU)) {
            content.setItem(createProductResource(destination, createdResources));
        }

        SimpleQuantity quantity;

        if (relationship.getType().equals(AttributeType.HAS_COMPONENT_PACK)) {
            quantity = new SimpleQuantity();
            quantity.setValue(1);
        } else {
            DataTypeProperty datatypeProperty = relationship.getDatatypeProperty();

            quantity = new SimpleQuantity();
            quantity.setValue(Double.valueOf(datatypeProperty.getValue()));
            quantity.setCode(Long.toString(datatypeProperty.getUnit().getId()));
            quantity.setUnit(datatypeProperty.getUnit().getPreferredTerm());
            quantity.setSystem(FhirCodeSystemUri.SNOMED_CT_SYSTEM_URI.getUri());
        }

        content.setAmount(quantity);

    }

    private void addIngredient(Medication medication, Collection<Relationship> relationships,
            List<Resource> createdResources) {
        Relationship iai = relationships.stream()
            .filter(r -> r.getType().equals(AttributeType.HAS_INTENDED_ACTIVE_INGREDIENT))
            .findFirst()
            .get();

        MedicationIngredientComponentExtension ingredient = new MedicationIngredientComponentExtension();

        medication.addIngredient(ingredient);

        ingredient.setItem(createSubstanceResource(iai.getDestination(), createdResources));

        Relationship hasBoss = relationships.stream()
            .filter(r -> r.getType().equals(AttributeType.HAS_AUSTRALIAN_BOSS))
            .findFirst()
            .orElse(null);
        if (hasBoss != null) {
            if (hasBoss.getDestination().getId() != iai.getDestination().getId()) {
                ingredient.setBasisOfStrengthSubstance(hasBoss.getDestination().toCoding());
                createSubstanceResource(hasBoss.getDestination(), createdResources);
            }

            DataTypeProperty d = hasBoss.getDatatypeProperty();

            Concept denominatorUnit = d.getUnit().getSingleDestination(AttributeType.HAS_DENOMINATOR_UNITS);
            Concept numeratorUnit = d.getUnit().getSingleDestination(AttributeType.HAS_NUMERATOR_UNITS);

            Ratio value = new Ratio();

            Quantity denominator = new Quantity(1L);

            if (denominatorUnit != null) {
                denominator.setCode(Long.toString(denominatorUnit.getId()));
                denominator.setUnit(denominatorUnit.getPreferredTerm());
                denominator.setSystem(FhirCodeSystemUri.SNOMED_CT_SYSTEM_URI.getUri());
            }
            value.setDenominator(denominator);

            Quantity numerator = new Quantity(Double.parseDouble(d.getValue()));
            numerator.setCode(Long.toString(numeratorUnit.getId()));
            numerator.setUnit(numeratorUnit.getPreferredTerm());
            numerator.setSystem(FhirCodeSystemUri.SNOMED_CT_SYSTEM_URI.getUri());
            value.setNumerator(numerator);

            ingredient.setAmount(value);
        }
    }

    private Reference toReference(Concept concept, String resourceType) {
        Reference reference;

        if (referenceCache.containsKey(concept.getId())) {
            reference = referenceCache.get(concept.getId());
        } else {
            reference = new Reference(resourceType + "/" + concept.getId());
            reference.setDisplay(concept.getPreferredTerm());
            referenceCache.put(concept.getId(), reference);
        }

        return reference;
    }

    private ExtendedReference toExtendedMedicationReference(Concept concept, List<Resource> createdResources) {
        ExtendedReference reference;
        if (extendedReferenceCache.containsKey(concept.getId())) {
            reference = extendedReferenceCache.get(concept.getId());
        } else {
            reference =
                    new ExtendedReference("Medication/" + concept.getId());
            reference.setDisplay(concept.getPreferredTerm());
            addParentExtensions(concept, reference, new HashSet<>(), createdResources);
            reference.setMedicationResourceType(concept.getMedicationType().getCode());
            reference.setMedicationResourceStatus(
                new Enumeration<MedicationStatus>(new MedicationStatusEnumFactory(), concept.getStatus()));
            reference.setLastModified(new DateType(concept.getLastModified()));

            addHistoicalAssociations(concept, reference, "Medication");

            addGeneralizedMedicineExtensions(concept, reference.getGeneralizedMedicine(), createdResources);

            CodeableConcept brand = concept.getBrand();
            if (brand != null) {
                reference.setBrand(brand);
            }
            extendedReferenceCache.put(concept.getId(), reference);
        }
        return reference;
    }
}
