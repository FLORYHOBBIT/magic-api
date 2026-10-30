export default {
    flow: {
        title: 'Flow Info',
        name: 'Flow',
        status: { title: 'Run Status' },
        msg: { title: 'Message Center' },
        settings: { title: 'Integration Settings' },
        form: {
            name: 'Flow Name',
            path: 'Flow Path',
            placeholder: {
                name: 'Please Enter Flow Name',
                path: 'Please Enter Flow Path',
                description: 'Please Enter Flow Description'
            }
        },
        debug: {
            title: 'Debug Run',
            input: 'Debug input (JSON, referenced as input)',
            inputPlaceholder: '{"patientId": "1001"}',
            inputInvalid: 'Debug input is not valid JSON: {0}',
            empty: 'Click "Run" to execute the current flow (accesses the database)',
            start: 'Running flow "{0}"',
            success: 'Succeeded in {0} ms',
            failure: 'Failed in {0} ms: {1}',
            node: 'Node',
            type: 'Type',
            status: 'Status',
            duration: 'Duration',
            next: 'Next'
        }
    }
}
