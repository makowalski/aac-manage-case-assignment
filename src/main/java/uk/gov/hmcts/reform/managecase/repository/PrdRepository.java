package uk.gov.hmcts.reform.managecase.repository;

import uk.gov.hmcts.reform.managecase.client.prd.FindUsersByOrganisationResponse;

public interface PrdRepository {

    FindUsersByOrganisationResponse findUsersByOrganisation();
}
