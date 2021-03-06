package au.gov.digitalhealth.medserve.server.resourceprovider;

import java.io.IOException;

import org.hl7.fhir.dstu3.model.IdType;
import org.hl7.fhir.dstu3.model.Substance;
import org.hl7.fhir.instance.model.api.IBaseResource;

import au.gov.digitalhealth.medserve.server.bundleprovider.TextSearchBundleProvider;
import au.gov.digitalhealth.medserve.server.index.Index;
import au.gov.digitalhealth.medserve.server.indexbuilder.constants.FieldNames;
import ca.uhn.fhir.model.api.annotation.Description;
import ca.uhn.fhir.rest.annotation.Count;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.DateAndListParam;
import ca.uhn.fhir.rest.param.StringAndListParam;
import ca.uhn.fhir.rest.param.StringOrListParam;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.server.IResourceProvider;

public class SubstanceResourceProvider implements IResourceProvider {
    private Index index;

    public SubstanceResourceProvider(Index index) {
        this.index = index;
    }

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return Substance.class;
    }

    @Read()
    public Substance getResourceById(@IdParam IdType theId) throws IOException {
        return index.getResourceById(Substance.class, theId.getIdPart());
    }

    @Search(type = Substance.class)
    public IBundleProvider searchByText(
            @OptionalParam(name = Substance.SP_CODE) @Description(shortDefinition = "Search the resource's codings") TokenAndListParam code,
            @OptionalParam(name = "_text") @Description(shortDefinition = "Search of the resource narrative") StringAndListParam text,
            @OptionalParam(name = Substance.SP_STATUS) @Description(shortDefinition = "Status of the substance, active, inactive (meaning no longer available) or entered-in-error") StringOrListParam status,
            @OptionalParam(name = FieldNames.LAST_MODIFIED) @Description(shortDefinition = "Date the underlying code system's content for this substance was last modified") DateAndListParam lastModified,
            @Count Integer theCount) throws IOException {

        return new TextSearchBundleProvider(Substance.class, index, code, text, status, lastModified, theCount);
    }
}
